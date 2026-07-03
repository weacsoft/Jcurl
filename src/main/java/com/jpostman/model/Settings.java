package com.jpostman.model;

/**
 * 用户设置 — 存储在 settings.json 中。
 * <p>
 * 包含主题、字体大小、历史记录上限等配置项。
 */
public class Settings {

    /** 主题: "light" 或 "dark" */
    private String theme = "light";

    /** 字体大小 */
    private int fontSize = 12;

    /** 历史记录上限 */
    private int historyLimit = 500;

    /** 默认请求超时 (秒) */
    private int defaultTimeout = 30;

    public Settings() {
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public int getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(int defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }
}
