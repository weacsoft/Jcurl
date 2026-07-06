package com.jcurl2.plugin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件元数据 — 描述一个已加载插件的信息。
 * <p>
 * 由 {@link PluginManager} 在编译并加载插件后创建,记录插件的基本信息与已注册的扩展点列表。
 */
public class Plugin {

    /** 插件 ID(源文件名去掉 .java 后缀,或 @JcurlPlugin.name) */
    private String id;
    /** 插件名称 */
    private String name;
    /** 插件描述 */
    private String description;
    /** 版本 */
    private String version;
    /** 作者 */
    private String author;
    /** 源文件路径 */
    private String sourceFile;
    /** 是否启用 */
    private boolean enabled = true;
    /** 加载状态 */
    private LoadStatus status = LoadStatus.UNLOADED;
    /** 加载错误信息(status=FAILED 时有值) */
    private String errorMessage;
    /** 已注册的扩展点类名列表 */
    private final List<String> extensionPoints = new ArrayList<>();
    /** 加载时间 */
    private LocalDateTime loadedAt;

    public enum LoadStatus { UNLOADED, LOADED, FAILED, DISABLED }

    public Plugin() {}

    public Plugin(String id, String name, String sourceFile) {
        this.id = id;
        this.name = name;
        this.sourceFile = sourceFile;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LoadStatus getStatus() { return status; }
    public void setStatus(LoadStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public List<String> getExtensionPoints() { return extensionPoints; }
    public LocalDateTime getLoadedAt() { return loadedAt; }
    public void setLoadedAt(LocalDateTime loadedAt) { this.loadedAt = loadedAt; }
}
