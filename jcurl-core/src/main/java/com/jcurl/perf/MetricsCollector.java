package com.jcurl.perf;

import com.tdunning.math.stats.TDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 性能指标采集器 — 实时采集并计算性能测试指标。
 * <p>
 * 使用 T-Digest 算法计算延迟百分位(p50/p90/p95/p99),内存占用恒定,精度可接受。
 * <p>
 * 线程安全:多个虚拟用户并发上报采样数据,使用 AtomicLong/LongAdder 保证计数安全,
 * T-Digest 本身是线程安全的(内部使用同步)。
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    /** T-Digest 压缩参数(越大精度越高,默认 100) */
    private static final double TDIGEST_COMPRESSION = 100.0;

    // 延迟统计
    private final TDigest latencyDigest = TDigest.createAvlTreeDigest(TDIGEST_COMPRESSION);

    // 计数器
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicLong minLatencyMs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatencyMs = new AtomicLong(0);

    // 时间范围
    private final long startTimeMs;
    private volatile long endTimeMs;

    public MetricsCollector() {
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * 上报一个采样结果。
     *
     * @param sample 采样数据
     */
    public synchronized void record(Sample sample) {
        totalRequests.increment();

        if (sample.isSuccess()) {
            successCount.increment();
        } else {
            failureCount.increment();
        }

        long elapsed = sample.getElapsedMs();
        if (elapsed >= 0) {
            latencyDigest.add(elapsed);
            totalLatencyMs.addAndGet(elapsed);
            // 更新最小值
            minLatencyMs.accumulateAndGet(elapsed, Math::min);
            // 更新最大值
            maxLatencyMs.accumulateAndGet(elapsed, Math::max);
        }
    }

    /** 标记测试结束 */
    public void finish() {
        this.endTimeMs = System.currentTimeMillis();
    }

    /**
     * 获取当前实时指标快照。
     *
     * @return 指标快照
     */
    public synchronized MetricsSnapshot snapshot() {
        long now = System.currentTimeMillis();
        long elapsed = (endTimeMs > 0 ? endTimeMs : now) - startTimeMs;
        double elapsedSeconds = elapsed / 1000.0;

        long total = totalRequests.sum();
        long success = successCount.sum();
        long failure = failureCount.sum();

        double rps = elapsedSeconds > 0 ? total / elapsedSeconds : 0;
        double avgLatency = total > 0 ? (double) totalLatencyMs.get() / total : 0;
        double errorRate = total > 0 ? (double) failure / total * 100 : 0;

        return new MetricsSnapshot(
                total, success, failure,
                rps, avgLatency, errorRate,
                minLatencyMs.get() == Long.MAX_VALUE ? 0 : minLatencyMs.get(),
                maxLatencyMs.get(),
                latencyDigest.quantile(0.50),
                latencyDigest.quantile(0.90),
                latencyDigest.quantile(0.95),
                latencyDigest.quantile(0.99),
                elapsedSeconds
        );
    }

    /**
     * 获取所有采样数据的延迟百分位(用于最终报告)。
     *
     * @param percentile 百分位(0.0 ~ 1.0)
     * @return 延迟值(毫秒)
     */
    public double getLatencyPercentile(double percentile) {
        return latencyDigest.quantile(percentile);
    }

    /** 获取总请求数 */
    public long getTotalRequests() {
        return totalRequests.sum();
    }
}
