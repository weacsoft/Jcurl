package com.jpostman.plugin;

/**
 * JPostman 插件接口 — 所有插件必须实现此接口。
 */
public interface JPostmanPlugin {
    /** 插件加载时调用, 用于初始化 */
    void onLoad();
    /** 插件卸载时调用, 用于资源释放 */
    void onUnload();
    /** 返回插件名称 */
    String getName();
    /** 返回插件版本 */
    String getVersion();
    /** 返回插件描述 */
    String getDescription();
}
