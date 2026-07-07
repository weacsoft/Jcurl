package com.jcurl.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 插件管理器 — 核心插件生命周期管理。
 * <p>
 * 职责:
 * <ul>
 *   <li>扫描插件目录({@code .api-client/plugins/})中的 .java 源文件</li>
 *   <li>编译源文件(去除注释后编译)并加载类</li>
 *   <li>发现实现 {@link ExtensionPoint} 的类并实例化</li>
 *   <li>注册到 {@link ExtensionRegistry}</li>
 *   <li>管理插件启用/禁用/重载</li>
 * </ul>
 * <p>
 * 插件加载流程:
 * <pre>
 * 扫描目录 → 读取源码 → 去除注释 → 编译 → 加载类 → 查找ExtensionPoint实现 → 实例化 → 注册
 * </pre>
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Path pluginsDir;
    private final PluginContext pluginContext;
    private final JavaSourceCompiler compiler;
    private final ExtensionRegistry registry;

    /** 插件 ID → Plugin 元数据 */
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();

    /** 插件 ID → 编译后的 ClassLoader(卸载时需关闭) */
    private final Map<String, ClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();

    /** 插件 ID → 已实例化的扩展对象列表 */
    private final Map<String, List<Object>> pluginInstances = new ConcurrentHashMap<>();

    public PluginManager(Path pluginsDir, PluginContext pluginContext) {
        this.pluginsDir = pluginsDir;
        this.pluginContext = pluginContext;
        this.compiler = new JavaSourceCompiler();
        this.registry = new ExtensionRegistry();
    }

    /** 获取扩展注册表 */
    public ExtensionRegistry getRegistry() {
        return registry;
    }

    /** 插件编译器是否可用 */
    public boolean isCompilerAvailable() {
        return compiler.isAvailable();
    }

    /** 获取所有已加载插件列表 */
    public List<Plugin> listPlugins() {
        return new ArrayList<>(plugins.values());
    }

    /** 获取指定插件 */
    public Plugin getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    // ==================== 加载/卸载 ====================

    /**
     * 扫描插件目录并加载所有 .java 插件。
     *
     * @return 加载成功的插件数量
     */
    public int loadAll() {
        ensurePluginsDir();
        int successCount = 0;
        try (Stream<Path> files = Files.list(pluginsDir)) {
            List<Path> allFiles = files
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".jar");
                    })
                    .sorted()
                    .toList();
            for (Path file : allFiles) {
                String fileName = file.getFileName().toString();
                try {
                    if (fileName.endsWith(".jar")) {
                        loadJarPlugin(file);
                    } else {
                        // 编译器不可用时跳过 .java 文件
                        if (!compiler.isAvailable()) {
                            log.warn("跳过 Java 源码插件 {} (编译器不可用)", fileName);
                            String pluginId = pluginIdFromFile(file);
                            Plugin plugin = plugins.computeIfAbsent(pluginId,
                                    k -> new Plugin(pluginId, pluginId, file.toString()));
                            plugin.setStatus(Plugin.LoadStatus.FAILED);
                            plugin.setErrorMessage("编译器不可用,请使用 JDK 启动或改用 .jar 插件");
                            continue;
                        }
                        loadPlugin(file);
                    }
                    successCount++;
                } catch (Exception e) {
                    log.error("加载插件失败: {}", file.getFileName(), e);
                    String pluginId = fileName.endsWith(".jar")
                            ? pluginIdFromJarFile(file) : pluginIdFromFile(file);
                    Plugin plugin = plugins.computeIfAbsent(pluginId,
                            k -> new Plugin(pluginId, pluginId, file.toString()));
                    plugin.setStatus(Plugin.LoadStatus.FAILED);
                    plugin.setErrorMessage(e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("扫描插件目录失败", e);
        }
        log.info("插件加载完成: 成功 {}/{}", successCount, plugins.size());
        return successCount;
    }

    /**
     * 加载单个插件源文件。
     *
     * @param sourceFile .java 源文件路径
     */
    public void loadPlugin(Path sourceFile) throws Exception {
        String pluginId = pluginIdFromFile(sourceFile);
        String sourceCode = Files.readString(sourceFile);
        String cleanedCode = stripComments(sourceCode);

        // 编译
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(pluginId, cleanedCode);
        ClassLoader classLoader = compiler.compile(sources);

        // 查找实现 ExtensionPoint 的类
        Class<?> compiledClass = classLoader.loadClass(pluginId);
        List<Object> instances = new ArrayList<>();
        List<String> extPoints = new ArrayList<>();

        // 检查主类本身
        if (ExtensionPoint.class.isAssignableFrom(compiledClass)) {
            Object instance = instantiate(compiledClass);
            if (instance != null) {
                instances.add(instance);
                extPoints.add(compiledClass.getSimpleName());
                registry.register(pluginId, (ExtensionPoint) instance);
            }
        }

        // 检查主类中的内部类
        for (Class<?> innerClass : compiledClass.getDeclaredClasses()) {
            if (ExtensionPoint.class.isAssignableFrom(innerClass) && !innerClass.isInterface()) {
                try {
                    Object instance = instantiate(innerClass);
                    if (instance != null) {
                        instances.add(instance);
                        extPoints.add(innerClass.getSimpleName());
                        registry.register(pluginId, (ExtensionPoint) instance);
                    }
                } catch (Exception e) {
                    log.warn("实例化内部类失败: {}", innerClass.getName(), e);
                }
            }
        }

        // 构建 Plugin 元数据
        Plugin plugin = new Plugin(pluginId, pluginId, sourceFile.toString());
        JcurlPlugin annotation = compiledClass.getAnnotation(JcurlPlugin.class);
        if (annotation != null) {
            if (!annotation.name().isEmpty()) plugin.setName(annotation.name());
            plugin.setDescription(annotation.description());
            plugin.setVersion(annotation.version());
            plugin.setAuthor(annotation.author());
            plugin.setEnabled(annotation.enabled());
        }
        plugin.getExtensionPoints().addAll(extPoints);
        plugin.setLoadedAt(LocalDateTime.now());
        plugin.setStatus(plugin.isEnabled() ? Plugin.LoadStatus.LOADED : Plugin.LoadStatus.DISABLED);

        // 如果禁用,从注册表移除
        if (!plugin.isEnabled()) {
            for (Object instance : instances) {
                registry.unregister(pluginId);
            }
        }

        plugins.put(pluginId, plugin);
        pluginClassLoaders.put(pluginId, classLoader);
        pluginInstances.put(pluginId, instances);

        log.info("插件已加载: id={}, name={}, extensions={}", pluginId, plugin.getName(), extPoints);
    }

    /**
     * 加载单个 JAR 插件。
     * JAR 插件是预编译的字节码,不需要编译器,直接通过 URLClassLoader 加载。
     *
     * @param jarFile .jar 文件路径
     */
    public void loadJarPlugin(Path jarFile) throws Exception {
        String pluginId = pluginIdFromJarFile(jarFile);

        // 先卸载旧版本(如果存在)
        if (plugins.containsKey(pluginId)) {
            unloadPlugin(pluginId);
        }

        // 创建 URLClassLoader 加载 JAR
        java.net.URL jarUrl = jarFile.toUri().toURL();
        URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{jarUrl},
                Thread.currentThread().getContextClassLoader());

        // 扫描 JAR 中所有 .class 文件,查找实现 ExtensionPoint 的类
        List<Object> instances = new ArrayList<>();
        List<String> extPoints = new ArrayList<>();

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || entryName.contains("$")) {
                    // 跳过内部类(后面会通过 getDeclaredClasses 查找)
                    continue;
                }
                String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (ExtensionPoint.class.isAssignableFrom(clazz) && !clazz.isInterface()
                            && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                        Object instance = instantiate(clazz);
                        if (instance != null) {
                            instances.add(instance);
                            extPoints.add(clazz.getSimpleName());
                            registry.register(pluginId, (ExtensionPoint) instance);
                        }
                    }
                } catch (Exception e) {
                    log.warn("加载 JAR 中的类失败: {}", className, e);
                }
            }
        }

        // 查找带 @JcurlPlugin 注解的类来获取元数据
        Plugin plugin = new Plugin(pluginId, pluginId, jarFile.toString());
        for (Object instance : instances) {
            JcurlPlugin annotation = instance.getClass().getAnnotation(JcurlPlugin.class);
            if (annotation != null) {
                if (!annotation.name().isEmpty()) plugin.setName(annotation.name());
                plugin.setDescription(annotation.description());
                plugin.setVersion(annotation.version());
                plugin.setAuthor(annotation.author());
                plugin.setEnabled(annotation.enabled());
                break;
            }
        }
        plugin.getExtensionPoints().addAll(extPoints);
        plugin.setLoadedAt(LocalDateTime.now());
        plugin.setStatus(plugin.isEnabled() ? Plugin.LoadStatus.LOADED : Plugin.LoadStatus.DISABLED);

        // 如果禁用,从注册表移除
        if (!plugin.isEnabled()) {
            for (Object instance : instances) {
                registry.unregister(pluginId);
            }
        }

        plugins.put(pluginId, plugin);
        pluginClassLoaders.put(pluginId, classLoader);
        pluginInstances.put(pluginId, instances);

        log.info("JAR 插件已加载: id={}, name={}, extensions={}", pluginId, plugin.getName(), extPoints);
    }

    /**
     * 卸载指定插件: 从注册表移除、关闭 ClassLoader、从插件列表中删除。
     * <p>
     * 卸载后插件不再出现在列表中(与 Swing 版行为一致)。
     * 源文件保留,可通过"安装"或重启重新加载。
     *
     * @param pluginId 插件 ID
     */
    public void unloadPlugin(String pluginId) {
        registry.unregister(pluginId);
        pluginInstances.remove(pluginId);
        ClassLoader cl = pluginClassLoaders.remove(pluginId);
        if (cl instanceof URLClassLoader urlCl) {
            try {
                urlCl.close();
            } catch (Exception e) {
                log.warn("关闭 ClassLoader 失败: {}", pluginId, e);
            }
        }
        plugins.remove(pluginId);
        log.info("插件已卸载: {}", pluginId);
    }

    /**
     * 重载指定插件(先卸载再加载)。
     *
     * @param pluginId 插件 ID
     */
    public void reloadPlugin(String pluginId) throws Exception {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null || plugin.getSourceFile() == null) {
            throw new IllegalArgumentException("插件不存在: " + pluginId);
        }
        unloadPlugin(pluginId);
        Path sourceFile = Path.of(plugin.getSourceFile());
        if (plugin.getSourceFile().endsWith(".jar")) {
            loadJarPlugin(sourceFile);
        } else {
            loadPlugin(sourceFile);
        }
        log.info("插件已重载: {}", pluginId);
    }

    /**
     * 重载所有插件。
     */
    public int reloadAll() {
        for (String pluginId : new ArrayList<>(plugins.keySet())) {
            try {
                reloadPlugin(pluginId);
            } catch (Exception e) {
                log.error("重载插件失败: {}", pluginId, e);
            }
        }
        return (int) plugins.values().stream()
                .filter(p -> p.getStatus() == Plugin.LoadStatus.LOADED)
                .count();
    }

    // ==================== 启用/禁用 ====================

    /**
     * 启用插件(重新注册扩展)。
     */
    public void enablePlugin(String pluginId) {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) return;
        plugin.setEnabled(true);
        plugin.setStatus(Plugin.LoadStatus.LOADED);

        // 重新注册已实例化的扩展
        List<Object> instances = pluginInstances.get(pluginId);
        if (instances != null) {
            for (Object instance : instances) {
                if (instance instanceof ExtensionPoint ep) {
                    registry.register(pluginId, ep);
                }
            }
        }
        log.info("插件已启用: {}", pluginId);
    }

    /**
     * 禁用插件(取消注册扩展,但保留实例)。
     */
    public void disablePlugin(String pluginId) {
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) return;
        plugin.setEnabled(false);
        plugin.setStatus(Plugin.LoadStatus.DISABLED);
        registry.unregister(pluginId);
        log.info("插件已禁用: {}", pluginId);
    }

    // ==================== 内部工具 ====================

    /** 确保插件目录存在 */
    private void ensurePluginsDir() {
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            log.error("创建插件目录失败: {}", pluginsDir, e);
        }
    }

    /** 从文件名生成插件 ID(去掉 .java 后缀) */
    private String pluginIdFromFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - 5);
    }

    /** 从 JAR 文件名生成插件 ID(去掉 .jar 后缀) */
    private String pluginIdFromJarFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - 4);
    }

    /**
     * 实例化插件类。
     * 优先尝试无参构造函数,其次尝试带 PluginContext 参数的构造函数。
     */
    private Object instantiate(Class<?> clazz) throws Exception {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        for (Constructor<?> ctor : constructors) {
            if (ctor.getParameterCount() == 0) {
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
        }
        for (Constructor<?> ctor : constructors) {
            if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == PluginContext.class) {
                ctor.setAccessible(true);
                return ctor.newInstance(pluginContext);
            }
        }
        log.warn("插件类 {} 没有合适的构造函数(无参或 PluginContext 参数)", clazz.getName());
        return null;
    }

    /**
     * 去除 Java 源码中的注释(单行 // 和多行 /* *​/ )。
     * <p>
     * 保留字符串字面量内的注释标记,避免误删。
     * 编译时去除注释可减小编译产物体积,也避免注释中的特殊字符影响编译。
     */
    static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        while (i < len) {
            char c = source.charAt(i);
            char next = (i + 1 < len) ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    result.append(c);
                }
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }
            if (inString) {
                result.append(c);
                if (c == '\\' && i + 1 < len) {
                    result.append(next);
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (inChar) {
                result.append(c);
                if (c == '\\' && i + 1 < len) {
                    result.append(next);
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                i++;
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = true;
                result.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                result.append(c);
                i++;
                continue;
            }

            result.append(c);
            i++;
        }
        return result.toString();
    }
}
