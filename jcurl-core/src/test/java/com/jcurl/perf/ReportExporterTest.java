package com.jcurl.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReportExporter 测试 — 验证 HTML/JSON/CSV 报告导出。
 */
class ReportExporterTest {

    @TempDir
    Path tempDir;

    private final ReportExporter exporter = new ReportExporter(new ObjectMapper());

    private MetricsSnapshot createSnapshot() {
        return new MetricsSnapshot(
                1000, 950, 50, 50.0, 120.5, 5.0,
                10, 500, 100.0, 200.0, 250.0, 400.0, 20.0
        );
    }

    private List<MetricsSnapshot> createHistory() {
        List<MetricsSnapshot> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(new MetricsSnapshot(
                    i * 100, i * 95, i * 5,
                    50.0 + i, 100.0 + i * 10, 5.0,
                    10 + i, 500 - i * 10,
                    90.0 + i, 180.0 + i * 5, 230.0 + i * 5, 380.0 + i * 5,
                    i * 1.0
            ));
        }
        return history;
    }

    private PerformanceTestConfig createConfig() {
        return PerformanceTestConfig.builder()
                .requestConfigJson("{}")
                .loadModel(LoadModel.CONSTANT)
                .maxVus(10)
                .durationSeconds(20)
                .build();
    }

    @Test
    @DisplayName("导出 HTML 报告")
    void exportHtmlReport() throws Exception {
        Path outputPath = tempDir.resolve("report.html");

        exporter.exportHtml(createSnapshot(), createHistory(), createConfig(), outputPath);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("<html"));
        assertTrue(content.contains("Jcurl 性能测试报告"));
        assertTrue(content.contains("1000"));  // totalRequests
        assertTrue(content.contains("50.0"));  // RPS
        assertTrue(content.contains("CONSTANT")); // load model
    }

    @Test
    @DisplayName("导出 JSON 报告")
    void exportJsonReport() throws Exception {
        Path outputPath = tempDir.resolve("report.json");

        exporter.exportJson(createSnapshot(), createHistory(), createConfig(), outputPath);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("\"totalRequests\""));
        assertTrue(content.contains("\"summary\""));
        assertTrue(content.contains("\"history\""));
        assertTrue(content.contains("\"config\""));
    }

    @Test
    @DisplayName("导出 CSV 报告")
    void exportCsvReport() throws Exception {
        Path outputPath = tempDir.resolve("report.csv");

        exporter.exportCsv(createSnapshot(), createHistory(), outputPath);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("时间(秒)"));
        assertTrue(content.contains("RPS"));
        assertTrue(content.contains("P50"));
        assertTrue(content.contains("100")); // first snapshot's rps ≈ 51
    }

    @Test
    @DisplayName("HTML 报告包含图表数据")
    void htmlReportContainsChartData() throws Exception {
        Path outputPath = tempDir.resolve("report_with_charts.html");

        exporter.exportHtml(createSnapshot(), createHistory(), createConfig(), outputPath);

        String content = Files.readString(outputPath);
        assertTrue(content.contains("rpsData"));
        assertTrue(content.contains("p50Data"));
        assertTrue(content.contains("p90Data"));
        assertTrue(content.contains("p95Data"));
        assertTrue(content.contains("p99Data"));
        assertTrue(content.contains("drawChart"));
    }

    @Test
    @DisplayName("空历史数据也能正常导出")
    void exportWithEmptyHistory() throws Exception {
        Path outputPath = tempDir.resolve("empty_report.html");

        exporter.exportHtml(createSnapshot(), List.of(), createConfig(), outputPath);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("1000"));
    }
}
