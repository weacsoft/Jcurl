package com.jpostman2.perf;

/**
 * 虚拟用户调度器 — 根据负载模型和时间计算当前应有的活跃 VU 数量。
 * <p>
 * 4 种负载模型的 VU 数量随时间变化:
 * <pre>
 * CONSTANT:  maxVus ──────────────────────
 *                      │                    │
 * RAMP_UP:         0 ──/── maxVus ──────────
 *                      │   /                 │
 * STAIRS:      0 ──┐   ┌──┐   ┌── maxVus ───
 *                  └───┘  └───┘
 * WAVE:    1 ──∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼∼  (正弦波)
 * </pre>
 */
public class VuScheduler {

    private final PerformanceTestConfig config;

    public VuScheduler(PerformanceTestConfig config) {
        this.config = config;
    }

    /**
     * 根据当前已运行时间计算目标 VU 数量。
     *
     * @param elapsedSeconds 已运行秒数
     * @return 当前应有的活跃 VU 数(至少 1)
     */
    public int getTargetVuCount(double elapsedSeconds) {
        int result = switch (config.getLoadModel()) {
            case CONSTANT -> calculateConstant(elapsedSeconds);
            case RAMP_UP -> calculateRampUp(elapsedSeconds);
            case STAIRS -> calculateStairs(elapsedSeconds);
            case WAVE -> calculateWave(elapsedSeconds);
        };
        return Math.max(1, Math.min(result, config.getMaxVus()));
    }

    /**
     * 固定负载:始终维持 maxVus。
     */
    private int calculateConstant(double elapsedSeconds) {
        return config.getMaxVus();
    }

    /**
     * 渐增负载:在 rampUpSeconds 内从 0 线性增加到 maxVus,之后维持 maxVus。
     */
    private int calculateRampUp(double elapsedSeconds) {
        int rampUp = config.getRampUpSeconds();
        if (rampUp <= 0 || elapsedSeconds >= rampUp) {
            return config.getMaxVus();
        }
        return (int) Math.ceil(config.getMaxVus() * elapsedSeconds / rampUp);
    }

    /**
     * 阶梯负载:将总时间分成 stairsSteps 段,每段 VU 数量递增。
     * <p>
     * 例如 maxVus=40, stairsSteps=4, duration=60s:
     * <pre>
     * 0-15s: 10 VU
     * 15-30s: 20 VU
     * 30-45s: 30 VU
     * 45-60s: 40 VU
     * </pre>
     */
    private int calculateStairs(double elapsedSeconds) {
        int steps = config.getStairsSteps();
        int duration = config.getDurationSeconds();
        int maxVus = config.getMaxVus();

        if (steps <= 0) return maxVus;

        double stepDuration = (double) duration / steps;
        int currentStep = (int) (elapsedSeconds / stepDuration);
        if (currentStep >= steps) currentStep = steps - 1;

        return (int) Math.ceil(maxVus * (currentStep + 1) / (double) steps);
    }

    /**
     * 波浪负载:VU 数量按正弦波在 1 和 maxVus 之间周期变化。
     * <p>
     * 公式: vu = mid + amplitude * sin(2π * t / period)
     * 其中 mid = (maxVus + 1) / 2, amplitude = (maxVus - 1) / 2
     */
    private int calculateWave(double elapsedSeconds) {
        int maxVus = config.getMaxVus();
        int cycles = config.getWaveCycles();
        int duration = config.getDurationSeconds();

        if (cycles <= 0 || duration <= 0) return maxVus;

        double period = (double) duration / cycles;
        double mid = (maxVus + 1) / 2.0;
        double amplitude = (maxVus - 1) / 2.0;
        double phase = 2 * Math.PI * elapsedSeconds / period;

        return (int) Math.round(mid + amplitude * Math.sin(phase));
    }
}
