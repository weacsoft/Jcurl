package com.jcurl.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 性能测试配置。
 */
public class LoadTestConfig {
    /** 虚拟用户数量 */
    private int virtualUsers = 10;
    /** 测试持续时间 (秒) */
    private int duration = 60;
    /** 负载模型: fixed / rampup / spike / peak */
    private String loadProfile = "fixed";
    /** 爬坡到达的最大虚拟用户数 (rampup 模式) */
    private int maxVirtualUsers = 50;
    /** 爬坡时间 (秒, rampup 模式) */
    private int rampUpDuration = 30;
    /** 峰值持续时间 (秒, peak/spike 模式) */
    private int peakDuration = 10;
    /** 请求间隔 (毫秒, 0 表示无间隔) */
    private int requestInterval = 0;
    /** 要测试的请求列表 */
    private List<RequestNode> requests = new ArrayList<>();
    /** 通过条件: 平均响应时间上限 (毫秒, 0 表示不检查) */
    private long maxAvgResponseTime = 0;
    /** 通过条件: 错误率上限 (百分比, 0 表示不检查) */
    private double maxErrorRate = 0;

    // 所有 getter 和 setter
    public int getVirtualUsers() { return virtualUsers; }
    public void setVirtualUsers(int virtualUsers) { this.virtualUsers = virtualUsers; }
    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }
    public String getLoadProfile() { return loadProfile; }
    public void setLoadProfile(String loadProfile) { this.loadProfile = loadProfile; }
    public int getMaxVirtualUsers() { return maxVirtualUsers; }
    public void setMaxVirtualUsers(int maxVirtualUsers) { this.maxVirtualUsers = maxVirtualUsers; }
    public int getRampUpDuration() { return rampUpDuration; }
    public void setRampUpDuration(int rampUpDuration) { this.rampUpDuration = rampUpDuration; }
    public int getPeakDuration() { return peakDuration; }
    public void setPeakDuration(int peakDuration) { this.peakDuration = peakDuration; }
    public int getRequestInterval() { return requestInterval; }
    public void setRequestInterval(int requestInterval) { this.requestInterval = requestInterval; }
    public List<RequestNode> getRequests() { return requests; }
    public void setRequests(List<RequestNode> requests) { this.requests = requests != null ? requests : new ArrayList<>(); }
    public long getMaxAvgResponseTime() { return maxAvgResponseTime; }
    public void setMaxAvgResponseTime(long maxAvgResponseTime) { this.maxAvgResponseTime = maxAvgResponseTime; }
    public double getMaxErrorRate() { return maxErrorRate; }
    public void setMaxErrorRate(double maxErrorRate) { this.maxErrorRate = maxErrorRate; }
}
