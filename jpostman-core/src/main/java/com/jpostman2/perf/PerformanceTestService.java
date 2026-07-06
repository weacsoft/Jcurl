package com.jpostman2.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman2.service.HttpEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 性能测试服务 — Spring 集成层,封装 {@link PerformanceTestEngine}。
 * <p>
 * 提供性能测试的启动、取消、状态查询接口,供 UI 层调用。
 */
@Service
public class PerformanceTestService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTestService.class);

    private final HttpEngineService httpEngine;
    private final ObjectMapper objectMapper;

    private PerformanceTestEngine engine;

    public PerformanceTestService(HttpEngineService httpEngine, ObjectMapper objectMapper) {
        this.httpEngine = httpEngine;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动性能测试。
     *
     * @param config             测试配置
     * @param metricsCallback    实时指标回调(每秒)
     * @param completionCallback 结束回调
     */
    public synchronized void startTest(PerformanceTestConfig config,
                                       Consumer<MetricsSnapshot> metricsCallback,
                                       Consumer<MetricsSnapshot> completionCallback) {
        if (engine != null && engine.isRunning()) {
            throw new IllegalStateException("已有性能测试正在运行");
        }
        engine = new PerformanceTestEngine(httpEngine, objectMapper);
        engine.start(config, metricsCallback, completionCallback);
    }

    /** 取消当前测试 */
    public synchronized void cancelTest() {
        if (engine != null && engine.isRunning()) {
            engine.cancel();
        }
    }

    /** 是否正在运行 */
    public boolean isRunning() {
        return engine != null && engine.isRunning();
    }

    /** 获取当前指标快照 */
    public MetricsSnapshot getSnapshot() {
        return engine != null ? engine.getSnapshot() : null;
    }
}
