package com.jpostman2.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 扩展点注册表 — 按扩展点类型存储和管理已注册的扩展实例。
 * <p>
 * 使用 {@link CopyOnWriteArrayList} 保证线程安全(插件可能在运行时动态加载/卸载)。
 * 每种扩展点类型维护一个独立的列表,宿主程序通过 {@link #getExtensions} 获取当前已注册的所有扩展。
 */
public class ExtensionRegistry {

    /** 扩展点类型 → 扩展实例列表 */
    private final Map<Class<? extends ExtensionPoint>, List<ExtensionPoint>> registry = new ConcurrentHashMap<>();

    /** 插件 ID → 该插件注册的所有扩展实例(用于卸载时清理) */
    private final Map<String, List<ExtensionPoint>> pluginExtensions = new ConcurrentHashMap<>();

    /**
     * 注册扩展实例。
     *
     * @param pluginId       所属插件 ID
     * @param extensionPoint 扩展实例
     */
    public synchronized void register(String pluginId, ExtensionPoint extensionPoint) {
        Class<?> clazz = extensionPoint.getClass();
        // 查找该类实现的所有 ExtensionPoint 子接口
        for (Class<?> iface : clazz.getInterfaces()) {
            if (ExtensionPoint.class.isAssignableFrom(iface)) {
                @SuppressWarnings("unchecked")
                Class<? extends ExtensionPoint> extType = (Class<? extends ExtensionPoint>) iface;
                registry.computeIfAbsent(extType, k -> new CopyOnWriteArrayList<>()).add(extensionPoint);
            }
        }
        // 超类中的接口也检查
        Class<?> superclass = clazz.getSuperclass();
        while (superclass != null && superclass != Object.class) {
            for (Class<?> iface : superclass.getInterfaces()) {
                if (ExtensionPoint.class.isAssignableFrom(iface)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends ExtensionPoint> extType = (Class<? extends ExtensionPoint>) iface;
                    List<ExtensionPoint> list = registry.get(extType);
                    if (list != null && !list.contains(extensionPoint)) {
                        list.add(extensionPoint);
                    }
                }
            }
            superclass = superclass.getSuperclass();
        }

        pluginExtensions.computeIfAbsent(pluginId, k -> new CopyOnWriteArrayList<>()).add(extensionPoint);
    }

    /**
     * 卸载插件的所有扩展实例。
     *
     * @param pluginId 插件 ID
     */
    public synchronized void unregister(String pluginId) {
        List<ExtensionPoint> extensions = pluginExtensions.remove(pluginId);
        if (extensions == null) return;
        for (List<ExtensionPoint> list : registry.values()) {
            list.removeAll(extensions);
        }
    }

    /**
     * 获取指定扩展点类型的所有已注册实例。
     *
     * @param extensionType 扩展点接口 Class
     * @return 扩展实例列表(可能为空,不会为 null)
     */
    @SuppressWarnings("unchecked")
    public <T extends ExtensionPoint> List<T> getExtensions(Class<T> extensionType) {
        List<ExtensionPoint> list = registry.get(extensionType);
        if (list == null) return List.of();
        return (List<T>) (List<?>) list;
    }

    /** 清除所有注册 */
    public synchronized void clear() {
        registry.clear();
        pluginExtensions.clear();
    }
}
