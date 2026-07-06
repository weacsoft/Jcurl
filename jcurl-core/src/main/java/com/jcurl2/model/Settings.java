package com.jcurl2.model;

/**
 * 用户设置 — 主题、字体、历史限制等偏好。
 * <p>
 * 存储在 settings.json 中。
 */
public class Settings {

    /** 主题: light / dark */
    private String theme = "dark";

    /** 字体大小 */
    private int fontSize = 14;

    /** 历史记录上限 */
    private int historyLimit = 500;

    /** 当前激活的环境 ID */
    private String activeEnvironmentId;

    /** 请求/响应分割比例(0.0-1.0) */
    private double splitRatio = 0.4;

    /** 窗口宽度(持久化) */
    private double windowWidth = 1280;

    /** 窗口高度(持久化) */
    private double windowHeight = 820;

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = fontSize; }
    public int getHistoryLimit() { return historyLimit; }
    public void setHistoryLimit(int historyLimit) { this.historyLimit = historyLimit; }
    public String getActiveEnvironmentId() { return activeEnvironmentId; }
    public void setActiveEnvironmentId(String activeEnvironmentId) { this.activeEnvironmentId = activeEnvironmentId; }
    public double getSplitRatio() { return splitRatio; }
    public void setSplitRatio(double splitRatio) { this.splitRatio = splitRatio; }
    public double getWindowWidth() { return windowWidth; }
    public void setWindowWidth(double windowWidth) { this.windowWidth = windowWidth; }
    public double getWindowHeight() { return windowHeight; }
    public void setWindowHeight(double windowHeight) { this.windowHeight = windowHeight; }
}
