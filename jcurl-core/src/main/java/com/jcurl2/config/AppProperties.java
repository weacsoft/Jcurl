package com.jcurl2.config;

/**
 * Jcurl 应用配置属性。
 * <p>
 * 对应 application.yml 中 {@code jcurl2.*} 前缀的配置项。
 */
public class AppProperties {

    /** 数据存储根目录,默认 {@code ./.api-client/} */
    private String dataDir;

    /** 历史记录上限,默认 500 条 */
    private int historyLimit = 500;

    /** 默认主题: light / dark */
    private String theme = "dark";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }
}
