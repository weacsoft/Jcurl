package com.jcurl2.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.model.dto.RequestConfig;
import com.jcurl2.model.dto.ResponseData;
import com.jcurl2.service.HttpEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 性能测试引擎 — 协调 VU 调度、请求执行与指标采集。
 * <p>
 * 执行流程:
 * <ol>
 *   <li>创建线程池(大小 = maxVus)</li>
 *   <li>启动调度线程,根据 {@link VuScheduler} 计算当前目标 VU 数</li>
 *   <li>动态增减 VU 工作线程:每个 VU 循环执行请求 → 记录采样 → 等待间隔</li>
 *   <li>定期(每秒)通过回调上报 {@link MetricsSnapshot} 给 UI</li>
 *   <li>测试结束或取消时,等待所有 VU 完成,返回最终指标</li>
 * </ol>
 * <p>
 * 线程模型:
 * <pre>
 * ┌────────────────────────────────────────┐
 * │          Scheduler Thread               │  ← 每秒计算目标 VU 数
 * │  ┌─────┬─────┬─────┬─────┐             │
 * │  │VU-0 │VU-1 │VU-2 │VU-3 │             │  ← VU 工作线程(动态增减)
 * │  │req  │req  │req  │req  │             │
 * │  │ ↓   │ ↓   │ ↓   │ ↓   │             │
 * │  │wait │wait │wait │wait │             │
 * │  │req  │req  │...  │...  │             │
 * │  └─────┴─────┴─────┴─────┘             │
 * │          MetricsCollector               │  ← 采样数据汇总
 * └────────────────────────────────────────┘
 * </pre>
 */
public class PerformanceTestEngine {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTestEngine.class);

    private final HttpEngineService httpEngine;
    private final ObjectMapper objectMapper;

    /** 是否正在运行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 是否已取消 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** VU ID 生成器 */
    private final AtomicInteger vuIdGenerator = new AtomicInteger(0);

    /** 线程池(VU 工作线程) */
    private ExecutorService vuExecutor;

    /** 调度线程 */
    private ScheduledExecutorService scheduler;

    /** 活跃 VU Future 列表(用于动态管理) */
    private final List<Future<?>> activeVus = new CopyOnWriteArrayList<>();

    /** 指标采集器 */
    private MetricsCollector metricsCollector;

    /** 测试配置 */
    private PerformanceTestConfig config;

    /** VU 调度器 */
    private VuScheduler vuScheduler;

    /** 实时指标回调 */
    private Consumer<MetricsSnapshot> metricsCallback;

    /** 测试结束回调 */
    private Consumer<MetricsSnapshot> completionCallback;

    /** 测试开始时间 */
    private long startTimeMs;

    public PerformanceTestEngine(HttpEngineService httpEngine, ObjectMapper objectMapper) {
        this.httpEngine = httpEngine;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动性能测试。
     *
     * @param config            测试配置
     * @param metricsCallback   实时指标回调(每秒调用一次,null 则不回调)
     * @param completionCallback 测试结束回调(null 则不回调)
     */
    public void start(PerformanceTestConfig config,
                      Consumer<MetricsSnapshot> metricsCallback,
                      Consumer<MetricsSnapshot> completionCallback) {
        if (running.get()) {
            throw new IllegalStateException("性能测试已在运行中");
        }

        this.config = config;
        this.metricsCallback = metricsCallback;
        this.completionCallback = completionCallback;
        this.vuScheduler = new VuScheduler(config);
        this.metricsCollector = new MetricsCollector();
        this.startTimeMs = System.currentTimeMillis();
        this.running.set(true);
        this.cancelled.set(false);

        // 创建线程池
        vuExecutor = Executors.newFixedThreadPool(config.getMaxVus(), r -> {
            Thread t = new Thread(r);
            t.setName("Jcurl-VU-" + vuIdGenerator.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        // 创建调度线程
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Jcurl-PerfScheduler");
            t.setDaemon(true);
            return t;
        });

        log.info("性能测试启动: model={}, maxVus={}, duration={}s",
                config.getLoadModel(), config.getMaxVus(), config.getDurationSeconds());

        // 启动调度线程:每秒调整 VU 数量并上报指标
        scheduler.scheduleAtFixedRate(this::adjustAndReport, 0, 1, TimeUnit.SECONDS);

        // 定时结束测试
        scheduler.schedule(this::stop, config.getDurationSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 取消测试(立即停止)。
     */
    public void cancel() {
        cancelled.set(true);
        stop();
    }

    /**
     * 是否正在运行。
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取当前指标快照。
     */
    public MetricsSnapshot getSnapshot() {
        return metricsCollector != null ? metricsCollector.snapshot() : null;
    }

    // ==================== 内部调度 ====================

    /**
     * 每秒执行:调整 VU 数量 + 上报指标。
     */
    private void adjustAndReport() {
        try {
            if (!running.get()) return;

            double elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0;

            // 计算目标 VU 数
            int targetVuCount = vuScheduler.getTargetVuCount(elapsed);
            int currentVuCount = activeVus.size();

            // 增加 VU
            while (currentVuCount < targetVuCount && !cancelled.get()) {
                submitVu();
                currentVuCount++;
            }

            // VU 数量超过目标时,多余的 VU 在完成当前请求后自然退出(通过 cancelled 标志检查)
            // 不强制中断,避免请求半途而废

            // 上报指标
            if (metricsCallback != null) {
                MetricsSnapshot snapshot = metricsCollector.snapshot();
                metricsCallback.accept(snapshot);
            }
        } catch (Exception e) {
            log.error("调度周期异常", e);
        }
    }

    /**
     * 提交一个 VU 工作线程。
     */
    private void submitVu() {
        int vuId = vuIdGenerator.getAndIncrement();
        Future<?> future = vuExecutor.submit(() -> runVu(vuId));
        activeVus.add(future);
    }

    /**
     * VU 工作循环:持续执行请求直到测试结束或取消。
     */
    private void runVu(int vuId) {
        try {
            // 反序列化请求配置(每个 VU 独立副本,避免并发修改)
            RequestConfig requestConfig = objectMapper.readValue(
                    config.getRequestConfigJson(), RequestConfig.class);

            while (running.get() && !cancelled.get()) {
                // 检查是否超过目标 VU 数(多余的 VU 退出)
                double elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0;
                int targetVuCount = vuScheduler.getTargetVuCount(elapsed);
                if (vuId >= targetVuCount && elapsed > 1) {
                    break; // 超出目标数量,退出
                }

                long requestStart = System.currentTimeMillis();
                Sample sample = new Sample();
                sample.setStartTimestamp(requestStart);
                sample.setVuId(vuId);

                try {
                    ResponseData response = httpEngine.execute(requestConfig, null);
                    long elapsedMs = System.currentTimeMillis() - requestStart;

                    sample.setElapsedMs(elapsedMs);
                    sample.setStatusCode(response.getStatusCode());
                    sample.setSuccess(response.getStatusCode() >= 200 && response.getStatusCode() < 400);
                    if (response.getError() != null && !response.getError().isEmpty()) {
                        sample.setError(response.getError());
                    }
                } catch (Exception e) {
                    sample.setElapsedMs(System.currentTimeMillis() - requestStart);
                    sample.setStatusCode(0);
                    sample.setSuccess(false);
                    sample.setError(e.getMessage());
                }

                metricsCollector.record(sample);

                // 请求间隔
                if (config.getRequestIntervalMs() > 0) {
                    Thread.sleep(config.getRequestIntervalMs());
                }
            }
        } catch (Exception e) {
            log.error("VU-{} 异常退出", vuId, e);
        }
    }

    /**
     * 停止测试,清理资源。
     */
    private void stop() {
        if (!running.compareAndSet(true, false)) return;

        try {
            // 停止调度
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            }

            // 等待 VU 完成(最多等待 10 秒)
            if (vuExecutor != null) {
                vuExecutor.shutdown();
                if (!vuExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    vuExecutor.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeVus.clear();

            if (metricsCollector != null) {
                metricsCollector.finish();
            }

            MetricsSnapshot finalSnapshot = metricsCollector != null ? metricsCollector.snapshot() : null;
            log.info("性能测试结束: {}", finalSnapshot);

            if (completionCallback != null && finalSnapshot != null) {
                completionCallback.accept(finalSnapshot);
            }
        }
    }
}
