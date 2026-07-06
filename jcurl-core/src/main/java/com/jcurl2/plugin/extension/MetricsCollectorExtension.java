package com.jcurl2.plugin.extension;

import com.jcurl2.model.dto.RequestConfig;
import com.jcurl2.model.dto.ResponseData;
import com.jcurl2.plugin.ExtensionPoint;
import com.jcurl2.plugin.PluginContext;

/**
 * 指标采集扩展点 — 在性能测试中采集自定义指标。
 * <p>
 * 内置指标:DNS/TCP/TLS/TTFB/Total 耗时、状态码、响应大小。
 * 插件可通过此扩展点采集更多指标,如内存使用、CPU 占用、自定义业务指标等。
 */
public interface MetricsCollectorExtension extends ExtensionPoint {

    /**
     * 获取此采集器提供的指标名称列表。
     * <p>
     * 如 ["memory_usage", "cpu_load"],这些名称将出现在性能测试报告中。
     *
     * @return 指标名称列表
     */
    java.util.List<String> getMetricNames();

    /**
     * 在单次请求完成后采集指标。
     *
     * @param config   请求配置
     * @param response 响应数据
     * @param ctx      插件上下文
     * @return 指标名 → 指标值 的映射
     */
    java.util.Map<String, Double> collectMetrics(RequestConfig config, ResponseData response, PluginContext ctx);
}
