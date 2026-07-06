package com.jcurl.plugin;

/**
 * 插件元数据 — 描述已加载插件的状态信息。
 */
public class PluginInfo {
    private String name;
    private String version;
    private String description;
    private String filePath;
    private String status; // "loaded" / "disabled" / "error"
    private String errorMessage;

    // 构造器, getter, setter
    public PluginInfo() {}

    public PluginInfo(String name, String version, String description, String filePath, String status) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.filePath = filePath;
        this.status = status;
    }

    // 所有 getter 和 setter
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
