package com.jcurl.plugin;

/**
 * 扩展点标记接口 — 所有插件扩展点接口均继承此接口。
 * <p>
 * 插件类实现任意扩展点接口后,会被 {@link PluginManager} 自动发现并注册到对应的
 * {@link ExtensionRegistry} 中,供宿主程序按需调用。
 */
public interface ExtensionPoint {
}
