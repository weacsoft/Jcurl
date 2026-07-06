package com.jpostman2.perf;

/**
 * 性能指标快照 — 某一时刻的性能数据快照,供 UI 实时展示和最终报告使用。
 */
public class MetricsSnapshot {

    /** 总请求数 */
    private final long totalRequests;
    /** 成功请求数 */
    private final long successCount;
    /** 失败请求数 */
    private final long failureCount;
    /** 每秒请求数(RPS) */
    private final double rps;
    /** 平均延迟(毫秒) */
    private final double avgLatency;
    /** 错误率(%) */
    private final double errorRate;
    /** 最小延迟(毫秒) */
    private final long minLatency;
    /** 最大延迟(毫秒) */
    private final long maxLatency;
    /** P50 延迟(中位数,毫秒) */
    private final double p50;
    /** P90 延迟(毫秒) */
    private final double p90;
    /** P95 延迟(毫秒) */
    private final double p95;
    /** P99 延迟(毫秒) */
    private final double p99;
    /** 已运行时间(秒) */
    private final double elapsedSeconds;

    public MetricsSnapshot(long totalRequests, long successCount, long failureCount,
                           double rps, double avgLatency, double errorRate,
                           long minLatency, long maxLatency,
                           double p50, double p90, double p95, double p99,
                           double elapsedSeconds) {
        this.totalRequests = totalRequests;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.rps = rps;
        this.avgLatency = avgLatency;
        this.errorRate = errorRate;
        this.minLatency = minLatency;
        this.maxLatency = maxLatency;
        this.p50 = p50;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
        this.elapsedSeconds = elapsedSeconds;
    }

    public long getTotalRequests() { return totalRequests; }
    public long getSuccessCount() { return successCount; }
    public long getFailureCount() { return failureCount; }
    public double getRps() { return rps; }
    public double getAvgLatency() { return avgLatency; }
    public double getErrorRate() { return errorRate; }
    public long getMinLatency() { return minLatency; }
    public long getMaxLatency() { return maxLatency; }
    public double getP50() { return p50; }
    public double getP90() { return p90; }
    public double getP95() { return p95; }
    public double getP99() { return p99; }
    public double getElapsedSeconds() { return elapsedSeconds; }

    @Override
    public String toString() {
        return String.format("Requests=%d(OK=%d/FAIL=%d) RPS=%.1f Avg=%.1fms P50=%.1f P90=%.1f P95=%.1f P99=%.1f Err=%.1f%%",
                totalRequests, successCount, failureCount, rps, avgLatency, p50, p90, p95, p99, errorRate);
    }
}
