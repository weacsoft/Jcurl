package com.jpostman.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 性能测试结果。
 */
public class LoadTestResult {
    private long totalRequests;
    private long successRequests;
    private long failedRequests;
    private long totalResponseTime; // 总和, 用于计算平均
    private long minResponseTime = Long.MAX_VALUE;
    private long maxResponseTime;
    private long testDuration; // 实际运行时长 (毫秒)
    private List<Long> responseTimes = new ArrayList<>(); // 所有响应时间, 用于百分位计算
    private boolean passed = true;
    private String failureReason;

    // 瞬时监控数据 (每秒一个点)
    private List<MetricPoint> timeline = new ArrayList<>();

    public static class MetricPoint {
        public long timestamp; // 从测试开始的毫秒数
        public int currentVUs;
        public double avgResponseTime;
        public double errorRate;
        public double throughput; // req/s

        public MetricPoint(long timestamp, int currentVUs, double avgResponseTime, double errorRate, double throughput) {
            this.timestamp = timestamp;
            this.currentVUs = currentVUs;
            this.avgResponseTime = avgResponseTime;
            this.errorRate = errorRate;
            this.throughput = throughput;
        }
    }

    // getter/setter
    public long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
    public long getSuccessRequests() { return successRequests; }
    public void setSuccessRequests(long successRequests) { this.successRequests = successRequests; }
    public long getFailedRequests() { return failedRequests; }
    public void setFailedRequests(long failedRequests) { this.failedRequests = failedRequests; }
    public long getTotalResponseTime() { return totalResponseTime; }
    public void setTotalResponseTime(long totalResponseTime) { this.totalResponseTime = totalResponseTime; }
    public long getMinResponseTime() { return minResponseTime; }
    public void setMinResponseTime(long minResponseTime) { this.minResponseTime = minResponseTime; }
    public long getMaxResponseTime() { return maxResponseTime; }
    public void setMaxResponseTime(long maxResponseTime) { this.maxResponseTime = maxResponseTime; }
    public long getTestDuration() { return testDuration; }
    public void setTestDuration(long testDuration) { this.testDuration = testDuration; }
    public List<Long> getResponseTimes() { return responseTimes; }
    public void setResponseTimes(List<Long> responseTimes) { this.responseTimes = responseTimes; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public List<MetricPoint> getTimeline() { return timeline; }
    public void setTimeline(List<MetricPoint> timeline) { this.timeline = timeline; }

    public double getAverageResponseTime() {
        return totalRequests > 0 ? (double) totalResponseTime / totalRequests : 0;
    }
    public double getErrorRate() {
        return totalRequests > 0 ? (double) failedRequests / totalRequests * 100 : 0;
    }
    public double getThroughput() {
        return testDuration > 0 ? (double) totalRequests / (testDuration / 1000.0) : 0;
    }

    public long getPercentile(int p) {
        if (responseTimes.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(responseTimes);
        java.util.Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
