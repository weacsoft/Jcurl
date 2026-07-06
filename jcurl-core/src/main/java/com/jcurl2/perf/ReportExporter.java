package com.jcurl2.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 性能测试报告导出器 — 支持 HTML、JSON、CSV 三种格式。
 * <p>
 * HTML: 包含样式化表格和内联图表的可独立打开的 HTML 文件。
 * JSON: 结构化数据,便于程序解析。
 * CSV: 简单表格,适合导入 Excel。
 */
@Component
public class ReportExporter {

    private static final Logger log = LoggerFactory.getLogger(ReportExporter.class);

    private final ObjectMapper objectMapper;

    public ReportExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 导出 HTML 报告。
     *
     * @param snapshot 最终指标快照
     * @param history  历史快照列表(每秒一个,用于图表数据)
     * @param config   测试配置
     * @param outputPath 输出路径
     */
    public void exportHtml(MetricsSnapshot snapshot, List<MetricsSnapshot> history,
                           PerformanceTestConfig config, Path outputPath) throws IOException {
        StringBuilder html = new StringBuilder(8192);

        html.append("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <title>Jcurl2 性能测试报告</title>
                <style>
                    body { font-family: 'Segoe UI', sans-serif; margin: 20px; background: #f5f5f5; }
                    h1 { color: #333; }
                    .summary { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin: 20px 0; }
                    .card { background: white; padding: 16px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                    .card .label { color: #888; font-size: 12px; }
                    .card .value { font-size: 24px; font-weight: bold; color: #2563eb; }
                    .card.error .value { color: #dc2626; }
                    .card.success .value { color: #16a34a; }
                    table { width: 100%%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; }
                    th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid #eee; }
                    th { background: #f8f8f8; font-weight: 600; }
                    .chart-container { background: white; padding: 16px; border-radius: 8px; margin: 16px 0; }
                    canvas { width: 100%%; height: 200px; }
                    .footer { color: #888; font-size: 12px; margin-top: 20px; }
                </style>
            </head>
            <body>
            <h1>Jcurl2 性能测试报告</h1>
            <p>生成时间: %s | 负载模型: %s | 持续时间: %ds | 最大VU: %d</p>
            """.formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                config.getLoadModel(), config.getDurationSeconds(), config.getMaxVus()));

        // 摘要卡片
        html.append("<div class=\"summary\">");
        appendCard(html, "总请求数", String.valueOf(snapshot.getTotalRequests()), "");
        appendCard(html, "成功请求", String.valueOf(snapshot.getSuccessCount()), "success");
        appendCard(html, "失败请求", String.valueOf(snapshot.getFailureCount()),
                snapshot.getFailureCount() > 0 ? "error" : "");
        appendCard(html, "RPS", String.format("%.1f", snapshot.getRps()), "");
        appendCard(html, "平均延迟", String.format("%.1f ms", snapshot.getAvgLatency()), "");
        appendCard(html, "P50 延迟", String.format("%.1f ms", snapshot.getP50()), "");
        appendCard(html, "P90 延迟", String.format("%.1f ms", snapshot.getP90()), "");
        appendCard(html, "P95 延迟", String.format("%.1f ms", snapshot.getP95()), "");
        appendCard(html, "P99 延迟", String.format("%.1f ms", snapshot.getP99()), "");
        appendCard(html, "最小延迟", snapshot.getMinLatency() + " ms", "");
        appendCard(html, "最大延迟", snapshot.getMaxLatency() + " ms", "");
        appendCard(html, "错误率", String.format("%.2f%%", snapshot.getErrorRate()),
                snapshot.getErrorRate() > 0 ? "error" : "success");
        html.append("</div>");

        // RPS 图表
        html.append("<div class=\"chart-container\"><h3>RPS 趋势</h3><canvas id=\"rpsChart\"></canvas></div>");
        // 延迟图表
        html.append("<div class=\"chart-container\"><h3>延迟趋势 (P50/P90/P95/P99)</h3><canvas id=\"latencyChart\"></canvas></div>");

        // 数据表格
        html.append("<h3>详细指标</h3><table><tbody>");
        appendRow(html, "总请求数", snapshot.getTotalRequests());
        appendRow(html, "成功请求", snapshot.getSuccessCount());
        appendRow(html, "失败请求", snapshot.getFailureCount());
        appendRow(html, "RPS (每秒请求数)", String.format("%.2f", snapshot.getRps()));
        appendRow(html, "平均延迟", String.format("%.2f ms", snapshot.getAvgLatency()));
        appendRow(html, "P50 (中位数)", String.format("%.2f ms", snapshot.getP50()));
        appendRow(html, "P90", String.format("%.2f ms", snapshot.getP90()));
        appendRow(html, "P95", String.format("%.2f ms", snapshot.getP95()));
        appendRow(html, "P99", String.format("%.2f ms", snapshot.getP99()));
        appendRow(html, "最小延迟", snapshot.getMinLatency() + " ms");
        appendRow(html, "最大延迟", snapshot.getMaxLatency() + " ms");
        appendRow(html, "错误率", String.format("%.2f%%", snapshot.getErrorRate()));
        appendRow(html, "运行时间", String.format("%.1f 秒", snapshot.getElapsedSeconds()));
        html.append("</tbody></table>");

        // JavaScript 图表数据
        html.append("<script>");
        html.append("var rpsData=").append(toJsArray(history, "rps")).append(";");
        html.append("var p50Data=").append(toJsArray(history, "p50")).append(";");
        html.append("var p90Data=").append(toJsArray(history, "p90")).append(";");
        html.append("var p95Data=").append(toJsArray(history, "p95")).append(";");
        html.append("var p99Data=").append(toJsArray(history, "p99")).append(";");
        html.append("""
            function drawChart(canvasId, datasets, labels) {
                var canvas = document.getElementById(canvasId);
                if (!canvas) return;
                var ctx = canvas.getContext('2d');
                var w = canvas.width = canvas.offsetWidth;
                var h = canvas.height = canvas.offsetHeight;
                ctx.clearRect(0, 0, w, h);
                if (datasets.length === 0 || datasets[0].length === 0) return;
                var maxVal = 0;
                datasets.forEach(function(d) { d.forEach(function(v) { if (v > maxVal) maxVal = v; }); });
                if (maxVal === 0) maxVal = 1;
                var colors = ['#2563eb', '#dc2626', '#16a34a', '#9333ea'];
                datasets.forEach(function(data, idx) {
                    ctx.strokeStyle = colors[idx % colors.length];
                    ctx.lineWidth = 2;
                    ctx.beginPath();
                    data.forEach(function(v, i) {
                        var x = (i / (data.length - 1)) * w;
                        var y = h - (v / maxVal) * h;
                        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
                    });
                    ctx.stroke();
                });
            }
            drawChart('rpsChart', [rpsData]);
            drawChart('latencyChart', [p50Data, p90Data, p95Data, p99Data]);
            """);
        html.append("</script>");

        html.append("<div class=\"footer\">Generated by Jcurl2 Performance Test Engine</div>");
        html.append("</body></html>");

        Files.writeString(outputPath, html.toString());
        log.info("HTML 报告已导出: {}", outputPath);
    }

    /**
     * 导出 JSON 报告。
     */
    public void exportJson(MetricsSnapshot snapshot, List<MetricsSnapshot> history,
                           PerformanceTestConfig config, Path outputPath) throws IOException {
        var report = new java.util.LinkedHashMap<String, Object>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("config", config);
        report.put("summary", snapshot);
        report.put("history", history);

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(outputPath, json);
        log.info("JSON 报告已导出: {}", outputPath);
    }

    /**
     * 导出 CSV 报告。
     */
    public void exportCsv(MetricsSnapshot snapshot, List<MetricsSnapshot> history,
                          Path outputPath) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("时间(秒),总请求数,成功数,失败数,RPS,平均延迟,P50,P90,P95,P99,错误率\n");
        for (MetricsSnapshot s : history) {
            csv.append(String.format("%.1f,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                    s.getElapsedSeconds(), s.getTotalRequests(), s.getSuccessCount(),
                    s.getFailureCount(), s.getRps(), s.getAvgLatency(),
                    s.getP50(), s.getP90(), s.getP95(), s.getP99(), s.getErrorRate()));
        }
        Files.writeString(outputPath, csv.toString());
        log.info("CSV 报告已导出: {}", outputPath);
    }

    // ==================== 内部工具 ====================

    private void appendCard(StringBuilder html, String label, String value, String cssClass) {
        html.append("<div class=\"card ").append(cssClass).append("\">");
        html.append("<div class=\"label\">").append(label).append("</div>");
        html.append("<div class=\"value\">").append(value).append("</div>");
        html.append("</div>");
    }

    private void appendRow(StringBuilder html, String label, Object value) {
        html.append("<tr><td>").append(label).append("</td><td>")
            .append(value).append("</td></tr>");
    }

    private String toJsArray(List<MetricsSnapshot> history, String field) {
        List<Double> values = new ArrayList<>();
        for (MetricsSnapshot s : history) {
            values.add(switch (field) {
                case "rps" -> s.getRps();
                case "p50" -> s.getP50();
                case "p90" -> s.getP90();
                case "p95" -> s.getP95();
                case "p99" -> s.getP99();
                default -> 0.0;
            });
        }
        return values.stream()
                .map(v -> String.format("%.2f", v))
                .reduce("[", (a, b) -> a.equals("[") ? a + b : a + "," + b) + "]";
    }
}
