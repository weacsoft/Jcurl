package com.jcurl.perf;

/**
 * 单次请求的采样结果 — 性能测试中每个虚拟用户每次请求的记录。
 */
public class Sample {

    /** 请求开始时间(毫秒时间戳) */
    private long startTimestamp;

    /** 请求耗时(毫秒) */
    private long elapsedMs;

    /** HTTP 状态码(0 表示请求失败未获得响应) */
    private int statusCode;

    /** 是否成功(2xx/3xx 为成功) */
    private boolean success;

    /** 错误信息(失败时) */
    private String error;

    /** 虚拟用户编号 */
    private int vuId;

    public Sample() {}

    public Sample(long startTimestamp, long elapsedMs, int statusCode, boolean success, String error, int vuId) {
        this.startTimestamp = startTimestamp;
        this.elapsedMs = elapsedMs;
        this.statusCode = statusCode;
        this.success = success;
        this.error = error;
        this.vuId = vuId;
    }

    public long getStartTimestamp() { return startTimestamp; }
    public void setStartTimestamp(long startTimestamp) { this.startTimestamp = startTimestamp; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getVuId() { return vuId; }
    public void setVuId(int vuId) { this.vuId = vuId; }
}
