package com.jcurl2.perf;

/**
 * 负载模型类型 — 性能测试的虚拟用户调度策略。
 */
public enum LoadModel {
    /** 固定负载:维持固定数量的 VU 持续发送请求 */
    CONSTANT,
    /** 渐增负载:从 0 线性增加到目标 VU 数,然后维持 */
    RAMP_UP,
    /** 阶梯负载:逐步增加 VU(如 10→20→30→40),每阶段维持一段时间 */
    STAIRS,
    /** 波浪负载:VU 数量按正弦波周期性变化(模拟流量高峰与低谷) */
    WAVE
}
