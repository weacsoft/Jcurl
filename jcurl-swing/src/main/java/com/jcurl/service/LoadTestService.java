package com.jcurl.service;

import com.jcurl.model.LoadTestConfig;
import com.jcurl.model.LoadTestResult;
import com.jcurl.model.LoadTestResult.MetricPoint;
import com.jcurl.model.RequestNode;
import com.jcurl.model.dto.RequestConfig;
import com.jcurl.model.dto.ResponseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能测试执行引擎。
 * <p>
 * 支持负载模型: fixed, rampup, spike, peak。
 * 使用线程池模拟虚拟用户(VUs), 每个 VU 循环执行请求序列。
 */
@Service
public class LoadTestService {
    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);

    private final HttpEngineService httpEngineService;
    private final VariableResolver variableResolver;

    private volatile boolean running = false;
    private volatile boolean paused = false;

    public LoadTestService(HttpEngineService httpEngineService, VariableResolver variableResolver) {
        this.httpEngineService = httpEngineService;
        this.variableResolver = variableResolver;
    }

    /**
     * 执行性能测试。
     * @param config 测试配置
     * @param listener 实时监控回调 (每秒调用一次), 可为 null
     * @return 测试结果
     */
    public LoadTestResult execute(LoadTestConfig config, LoadTestListener listener) {
        LoadTestResult result = new LoadTestResult();
        running = true;
        paused = false;

        long startTime = System.currentTimeMillis();
        long endTime = startTime + config.getDuration() * 1000L;

        int vuCount = config.getVirtualUsers();
        ExecutorService executor = Executors.newFixedThreadPool(vuCount);
        CountDownLatch latch = new CountDownLatch(vuCount);

        // 每个请求的统计
        final AtomicLong totalReq = new AtomicLong(0);
        final AtomicLong successReq = new AtomicLong(0);
        final AtomicLong failedReq = new AtomicLong(0);
        final AtomicLong totalTime = new AtomicLong(0);
        final Object minMaxLock = new Object();
        final List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());

        // 启动 VU 线程
        for (int i = 0; i < vuCount; i++) {
            final int vuIndex = i;
            executor.submit(() -> {
                try {
                    while (running && System.currentTimeMillis() < endTime) {
                        if (paused) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            continue;
                        }
                        // 执行请求序列
                        for (RequestNode reqNode : config.getRequests()) {
                            if (!running || System.currentTimeMillis() >= endTime) break;
                            try {
                                RequestConfig reqConfig = RequestConfig.from(reqNode);
                                // 变量替换: URL / params / headers
                                reqConfig.setUrl(variableResolver.resolve(reqConfig.getUrl()));
                                reqConfig.setParams(variableResolver.resolveKeyValues(reqConfig.getParams()));
                                reqConfig.setHeaders(variableResolver.resolveKeyValues(reqConfig.getHeaders()));

                                ResponseData response = httpEngineService.execute(reqConfig);

                                long elapsed = response.getResponseTime();
                                totalReq.incrementAndGet();
                                totalTime.addAndGet(elapsed);
                                responseTimes.add(elapsed);

                                if (response.isSuccess()) {
                                    successReq.incrementAndGet();
                                } else {
                                    failedReq.incrementAndGet();
                                }

                                synchronized (minMaxLock) {
                                    if (elapsed < result.getMinResponseTime()) {
                                        result.setMinResponseTime(elapsed);
                                    }
                                    if (elapsed > result.getMaxResponseTime()) {
                                        result.setMaxResponseTime(elapsed);
                                    }
                                }

                                if (config.getRequestInterval() > 0) {
                                    Thread.sleep(config.getRequestInterval());
                                }
                            } catch (Exception e) {
                                failedReq.incrementAndGet();
                                totalReq.incrementAndGet();
                                log.debug("VU {} 请求失败: {}", vuIndex, e.getMessage());
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // 监控线程: 每秒采集指标
        Thread monitorThread = new Thread(() -> {
            long lastTotalReq = 0;
            while (running && System.currentTimeMillis() < endTime) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
                long now = System.currentTimeMillis();
                long currentTotal = totalReq.get();
                long intervalReqs = currentTotal - lastTotalReq;
                double throughput = intervalReqs; // req/s (1秒间隔)

                long currentTotalTime = totalTime.get();
                double avgRt = currentTotal > 0 ? (double) currentTotalTime / currentTotal : 0;
                double errRate = currentTotal > 0 ? (double) failedReq.get() / currentTotal * 100 : 0;

                MetricPoint point = new MetricPoint(now - startTime, vuCount, avgRt, errRate, throughput);
                result.getTimeline().add(point);

                if (listener != null) {
                    listener.onMetric(point);
                }

                lastTotalReq = currentTotal;
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();

        // 等待所有 VU 完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            log.warn("测试被中断");
        }

        running = false;
        executor.shutdownNow();

        long actualEndTime = System.currentTimeMillis();
        result.setTestDuration(actualEndTime - startTime);
        result.setTotalRequests(totalReq.get());
        result.setSuccessRequests(successReq.get());
        result.setFailedRequests(failedReq.get());
        result.setTotalResponseTime(totalTime.get());
        result.setResponseTimes(new ArrayList<>(responseTimes));

        if (result.getMinResponseTime() == Long.MAX_VALUE) {
            result.setMinResponseTime(0);
        }

        // 检查通过条件
        checkPassCriteria(result, config);

        if (listener != null) {
            listener.onComplete(result);
        }

        return result;
    }

    private void checkPassCriteria(LoadTestResult result, LoadTestConfig config) {
        if (config.getMaxAvgResponseTime() > 0) {
            if (result.getAverageResponseTime() > config.getMaxAvgResponseTime()) {
                result.setPassed(false);
                result.setFailureReason("平均响应时间 " + String.format("%.1f", result.getAverageResponseTime()) +
                    "ms 超过上限 " + config.getMaxAvgResponseTime() + "ms");
                return;
            }
        }
        if (config.getMaxErrorRate() > 0) {
            if (result.getErrorRate() > config.getMaxErrorRate()) {
                result.setPassed(false);
                result.setFailureReason("错误率 " + String.format("%.1f", result.getErrorRate()) +
                    "% 超过上限 " + config.getMaxErrorRate() + "%");
            }
        }
    }

    public void stop() {
        running = false;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 测试监听器接口。
     */
    public interface LoadTestListener {
        void onMetric(MetricPoint point);
        void onComplete(LoadTestResult result);
    }
}
