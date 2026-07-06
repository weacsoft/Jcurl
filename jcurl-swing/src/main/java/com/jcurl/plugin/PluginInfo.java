package com.jcurl.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件元数据 — 描述已加载插件的状态信息。
 * <p>
 * 状态值: "loaded" / "disabled" / "failed" / "unloaded"
 */
public class PluginInfo {
    /** 插件 ID (文件名去掉后缀) */
    private String id;
    private String name;
    private String version;
    private String description;
    private String filePath;
    /** 状态: loaded / disabled / failed / unloaded */
    private String status;
    /** 是否启用 */
    private boolean enabled;
    /** 扩展点列表 (RequestInterceptor / ResponseProcessor / VariableFunction) */
    private List<String> extensionPoints = new ArrayList<>();
    private String errorMessage;

    public PluginInfo() {}

    public PluginInfo(String name, String version, String description, String filePath, String status) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.filePath = filePath;
        this.status = status;
    }

    // 所有 getter 和 setter
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getExtensionPoints() { return extensionPoints; }
    public void setExtensionPoints(List<String> extensionPoints) {
        this.extensionPoints = extensionPoints != null ? extensionPoints : new ArrayList<>();
    }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
