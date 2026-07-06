package com.jcurl2.perf;

/**
 * 性能测试配置 — 定义一次性能测试的全部参数。
 * <p>
 * 使用 Builder 模式构建,确保参数完整性。
 */
public class PerformanceTestConfig {

    /** 请求配置 JSON(序列化的 RequestConfig,包含 URL/Method/Headers/Body/Auth) */
    private String requestConfigJson;

    /** 负载模型 */
    private LoadModel loadModel = LoadModel.CONSTANT;

    /** 最大虚拟用户数 */
    private int maxVus = 10;

    /** 测试持续时间(秒) */
    private int durationSeconds = 60;

    /** 渐增时间(秒,RAMP_UP 模型:从 0 增到 maxVus 的时间) */
    private int rampUpSeconds = 10;

    /** 阶梯步数(STAIRS 模型:分成多少个阶梯) */
    private int stairsSteps = 4;

    /** 波浪周期数(WAVE 模型:完成多少个正弦波周期) */
    private int waveCycles = 3;

    /** 每个请求的间隔时间(毫秒,0 表示不等待,尽快发送) */
    private int requestIntervalMs = 0;

    /** 请求超时时间(秒) */
    private int requestTimeoutSeconds = 30;

    /** 并发线程池大小(通常等于 maxVus) */
    private int threadPoolSize = 10;

    // Getters
    public String getRequestConfigJson() { return requestConfigJson; }
    public LoadModel getLoadModel() { return loadModel; }
    public int getMaxVus() { return maxVus; }
    public int getDurationSeconds() { return durationSeconds; }
    public int getRampUpSeconds() { return rampUpSeconds; }
    public int getStairsSteps() { return stairsSteps; }
    public int getWaveCycles() { return waveCycles; }
    public int getRequestIntervalMs() { return requestIntervalMs; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getThreadPoolSize() { return threadPoolSize; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final PerformanceTestConfig config = new PerformanceTestConfig();

        public Builder requestConfigJson(String json) { config.requestConfigJson = json; return this; }
        public Builder loadModel(LoadModel model) { config.loadModel = model; return this; }
        public Builder maxVus(int vus) { config.maxVus = vus; return this; }
        public Builder durationSeconds(int seconds) { config.durationSeconds = seconds; return this; }
        public Builder rampUpSeconds(int seconds) { config.rampUpSeconds = seconds; return this; }
        public Builder stairsSteps(int steps) { config.stairsSteps = steps; return this; }
        public Builder waveCycles(int cycles) { config.waveCycles = cycles; return this; }
        public Builder requestIntervalMs(int ms) { config.requestIntervalMs = ms; return this; }
        public Builder requestTimeoutSeconds(int seconds) { config.requestTimeoutSeconds = seconds; return this; }
        public Builder threadPoolSize(int size) { config.threadPoolSize = size; return this; }

        public PerformanceTestConfig build() {
            if (config.requestConfigJson == null) {
                throw new IllegalArgumentException("requestConfigJson 不能为空");
            }
            if (config.maxVus < 1) {
                throw new IllegalArgumentException("maxVus 不能小于 1");
            }
            if (config.durationSeconds < 1) {
                throw new IllegalArgumentException("durationSeconds 不能小于 1");
            }
            return config;
        }
    }
}
