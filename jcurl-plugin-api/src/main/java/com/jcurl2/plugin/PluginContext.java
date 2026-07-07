package com.jcurl2.plugin;

import java.nio.file.Path;

/**
 * 插件上下文 — 为插件提供宿主程序的基本服务访问能力。
 * <p>
 * 这是最小化的共享接口,所有版本 (core/JavaFX/Swing) 都实现此接口。
 * core 版本通过 CorePluginContext (extends PluginContext) 提供额外服务访问。
 * <p>
 * 插件通过此接口访问宿主功能,避免直接依赖 Spring 容器。
 */
public interface PluginContext {

    /** 获取数据存储根目录 */
    Path getDataDir();

    /** 获取插件专属数据目录(每个插件独立,不会互相干扰) */
    Path getPluginDataDir(String pluginId);

    /** 记录日志(插件不应直接使用 SLF4J,通过此方法记录) */
    void log(String level, String message);

    /** 记录日志(带异常) */
    void log(String level, String message, Throwable throwable);
}
