package com.jpostman2.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能测试引擎测试 — 验证 VuScheduler、MetricsCollector 核心逻辑。
 */
class PerformanceTestEngineTest {

    // ==================== VuScheduler 测试 ====================

    @Nested
    @DisplayName("VuScheduler 负载模型")
    class VuSchedulerTest {

        @Test
        @DisplayName("CONSTANT: 始终维持 maxVus")
        void constantLoad() {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .requestConfigJson("{}")
                    .loadModel(LoadModel.CONSTANT)
                    .maxVus(20)
                    .durationSeconds(60)
                    .build();
            VuScheduler scheduler = new VuScheduler(config);

            assertEquals(20, scheduler.getTargetVuCount(0));
            assertEquals(20, scheduler.getTargetVuCount(30));
            assertEquals(20, scheduler.getTargetVuCount(59.9));
        }

        @Test
        @DisplayName("RAMP_UP: 线性增加")
        void rampUpLoad() {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .requestConfigJson("{}")
                    .loadModel(LoadModel.RAMP_UP)
                    .maxVus(40)
                    .durationSeconds(60)
                    .rampUpSeconds(10)
                    .build();
            VuScheduler scheduler = new VuScheduler(config);

            assertEquals(1, scheduler.getTargetVuCount(0));   // 最少 1
            assertEquals(4, scheduler.getTargetVuCount(1));   // 1/10 * 40 = 4
            assertEquals(20, scheduler.getTargetVuCount(5));  // 5/10 * 40 = 20
            assertEquals(40, scheduler.getTargetVuCount(10)); // rampUp 结束
            assertEquals(40, scheduler.getTargetVuCount(30)); // 维持
        }

        @Test
        @DisplayName("STAIRS: 阶梯递增")
        void stairsLoad() {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .requestConfigJson("{}")
                    .loadModel(LoadModel.STAIRS)
                    .maxVus(40)
                    .durationSeconds(60)
                    .stairsSteps(4)
                    .build();
            VuScheduler scheduler = new VuScheduler(config);

            // 0-15s: 10 VU
            assertEquals(10, scheduler.getTargetVuCount(0));
            assertEquals(10, scheduler.getTargetVuCount(14.9));
            // 15-30s: 20 VU
            assertEquals(20, scheduler.getTargetVuCount(15));
            assertEquals(20, scheduler.getTargetVuCount(29.9));
            // 30-45s: 30 VU
            assertEquals(30, scheduler.getTargetVuCount(30));
            assertEquals(30, scheduler.getTargetVuCount(44.9));
            // 45-60s: 40 VU
            assertEquals(40, scheduler.getTargetVuCount(45));
            assertEquals(40, scheduler.getTargetVuCount(59.9));
        }

        @Test
        @DisplayName("WAVE: 正弦波变化")
        void waveLoad() {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .requestConfigJson("{}")
                    .loadModel(LoadModel.WAVE)
                    .maxVus(20)
                    .durationSeconds(60)
                    .waveCycles(3)
                    .build();
            VuScheduler scheduler = new VuScheduler(config);

            // period = 60/3 = 20s, mid = 10.5, amplitude = 9.5
            // t=0: sin(0) = 0, vu = 10.5 → 11
            int vu0 = scheduler.getTargetVuCount(0);
            assertTrue(vu0 >= 10 && vu0 <= 12);

            // t=5 (1/4 period): sin(π/2) = 1, vu = 20
            int vu5 = scheduler.getTargetVuCount(5);
            assertTrue(vu5 >= 19 && vu5 <= 21);

            // t=10 (1/2 period): sin(π) = 0, vu ≈ 10
            int vu10 = scheduler.getTargetVuCount(10);
            assertTrue(vu10 >= 9 && vu10 <= 12);

            // t=15 (3/4 period): sin(3π/2) = -1, vu = 1
            int vu15 = scheduler.getTargetVuCount(15);
            assertTrue(vu15 >= 1 && vu15 <= 3);

            // 所有值在 1 到 maxVus 范围内
            for (double t = 0; t < 60; t += 0.5) {
                int vu = scheduler.getTargetVuCount(t);
                assertTrue(vu >= 1 && vu <= 20, "t=" + t + " vu=" + vu + " 超出范围");
            }
        }

        @Test
        @DisplayName("VU 数量不超过 maxVus")
        void vuNotExceedMax() {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .requestConfigJson("{}")
                    .loadModel(LoadModel.CONSTANT)
                    .maxVus(5)
                    .durationSeconds(30)
                    .build();
            VuScheduler scheduler = new VuScheduler(config);

            for (double t = 0; t < 30; t += 1) {
                assertTrue(scheduler.getTargetVuCount(t) <= 5);
            }
        }

        @Test
        @DisplayName("VU 数量至少为 1")
        void vuAtLeastOne() {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .requestConfigJson("{}")
                    .loadModel(LoadModel.RAMP_UP)
                    .maxVus(10)
                    .durationSeconds(30)
                    .rampUpSeconds(10)
                    .build();
            VuScheduler scheduler = new VuScheduler(config);

            assertEquals(1, scheduler.getTargetVuCount(0));
        }
    }

    // ==================== MetricsCollector 测试 ====================

    @Nested
    @DisplayName("MetricsCollector 指标采集")
    class MetricsCollectorTest {

        @Test
        @DisplayName("记录采样并获取快照")
        void recordAndSnapshot() {
            MetricsCollector collector = new MetricsCollector();

            collector.record(new Sample(1000, 50, 200, true, null, 0));
            collector.record(new Sample(2000, 100, 200, true, null, 1));
            collector.record(new Sample(3000, 150, 200, true, null, 2));
            collector.record(new Sample(4000, 200, 500, false, "Server Error", 3));
            collector.finish();

            MetricsSnapshot snapshot = collector.snapshot();

            assertEquals(4, snapshot.getTotalRequests());
            assertEquals(3, snapshot.getSuccessCount());
            assertEquals(1, snapshot.getFailureCount());
            assertEquals(25.0, snapshot.getErrorRate(), 0.1);
            assertEquals(50, snapshot.getMinLatency());
            assertEquals(200, snapshot.getMaxLatency());
            assertTrue(snapshot.getAvgLatency() > 0);
            assertTrue(snapshot.getP50() > 0);
            assertTrue(snapshot.getP90() > 0);
            assertTrue(snapshot.getP95() > 0);
            assertTrue(snapshot.getP99() > 0);
        }

        @Test
        @DisplayName("空采集器快照")
        void emptySnapshot() {
            MetricsCollector collector = new MetricsCollector();
            collector.finish();

            MetricsSnapshot snapshot = collector.snapshot();

            assertEquals(0, snapshot.getTotalRequests());
            assertEquals(0, snapshot.getSuccessCount());
            assertEquals(0, snapshot.getFailureCount());
            assertEquals(0, snapshot.getMinLatency());
            assertEquals(0, snapshot.getMaxLatency());
            assertEquals(0.0, snapshot.getErrorRate());
        }

        @Test
        @DisplayName("百分位计算准确性")
        void percentileAccuracy() {
            MetricsCollector collector = new MetricsCollector();

            // 添加 100 个样本,延迟 1-100ms
            for (int i = 1; i <= 100; i++) {
                collector.record(new Sample(i * 10, i, 200, true, null, 0));
            }
            collector.finish();

            MetricsSnapshot snapshot = collector.snapshot();

            // P50 应在 50 附近
            assertTrue(snapshot.getP50() >= 40 && snapshot.getP50() <= 60,
                    "P50=" + snapshot.getP50());
            // P90 应在 90 附近
            assertTrue(snapshot.getP90() >= 80 && snapshot.getP90() <= 100,
                    "P90=" + snapshot.getP90());
            // P99 应接近 100
            assertTrue(snapshot.getP99() >= 90 && snapshot.getP99() <= 100,
                    "P99=" + snapshot.getP99());
        }

        @Test
        @DisplayName("RPS 计算")
        void rpsCalculation() throws InterruptedException {
            MetricsCollector collector = new MetricsCollector();

            // 等待约 100ms
            Thread.sleep(100);

            collector.record(new Sample(System.currentTimeMillis(), 10, 200, true, null, 0));
            collector.record(new Sample(System.currentTimeMillis(), 10, 200, true, null, 1));
            collector.record(new Sample(System.currentTimeMillis(), 10, 200, true, null, 2));
            collector.finish();

            MetricsSnapshot snapshot = collector.snapshot();

            assertEquals(3, snapshot.getTotalRequests());
            assertTrue(snapshot.getRps() > 0, "RPS 应大于 0,实际=" + snapshot.getRps());
        }

        @Test
        @DisplayName("错误率计算")
        void errorRateCalculation() {
            MetricsCollector collector = new MetricsCollector();

            // 8 个成功,2 个失败
            for (int i = 0; i < 8; i++) {
                collector.record(new Sample(i * 10, 50, 200, true, null, i));
            }
            collector.record(new Sample(100, 50, 500, false, "err", 8));
            collector.record(new Sample(110, 50, 0, false, "timeout", 9));
            collector.finish();

            MetricsSnapshot snapshot = collector.snapshot();

            assertEquals(10, snapshot.getTotalRequests());
            assertEquals(8, snapshot.getSuccessCount());
            assertEquals(2, snapshot.getFailureCount());
            assertEquals(20.0, snapshot.getErrorRate(), 0.1);
        }
    }
}
