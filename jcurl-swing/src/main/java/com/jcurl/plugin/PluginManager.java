package com.jcurl.plugin;

import com.jcurl.plugin.ExtensionPoint;
import com.jcurl.plugin.JcurlPlugin;
import com.jcurl.plugin.PluginContext;
import com.jcurl.plugin.extension.MetricsCollectorExtension;
import com.jcurl.plugin.extension.RequestInterceptor;
import com.jcurl.plugin.extension.ResponseInterceptor;
import com.jcurl.plugin.extension.VariableFunctionExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件管理器 — 负责插件的发现、加载、卸载、启用/禁用/重载管理。
 * <p>
 * 使用共享模块 {@code jcurl-plugin-api} 的统一插件接口:
 * <ul>
 *   <li>插件标记: {@link ExtensionPoint} (所有扩展点接口均继承此接口)</li>
 *   <li>插件元数据: {@link JcurlPlugin} 注解</li>
 *   <li>扩展点: {@link RequestInterceptor}、{@link ResponseInterceptor}、
 *       {@link VariableFunctionExtension}、{@link MetricsCollectorExtension}</li>
 * </ul>
 * <p>
 * 支持两种插件形式:
 * <ul>
 *   <li><b>.java 源码插件</b>: 使用 {@link JavaCompiler} 在内存中编译 (需 JDK 环境),
 *       去除注释后编译,通过自定义 {@link MemoryClassLoader} 加载</li>
 *   <li><b>.jar 插件</b>: 使用 {@link URLClassLoader} 加载,扫描 JAR 中所有 .class 文件
 *       查找实现 {@link ExtensionPoint} 接口的类 (不依赖 ServiceLoader SPI)</li>
 * </ul>
 * <p>
 * 插件 ID = 文件名去掉后缀 (.java 或 .jar)。
 */
@Service
public class PluginManager {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Path pluginDir;
    private final JavaCompiler compiler;
    private final boolean compilerAvailable;

    /** 懒加载的编译 classpath (仅在有 .java 插件需要编译时才提取 fat jar classpath) */
    private volatile String compileClasspath;

    /** 插件上下文 — 传递给扩展点方法,提供数据目录与日志能力 */
    private final PluginContext pluginContext;

    /** 插件 ID → PluginEntry */
    private final ConcurrentHashMap<String, PluginEntry> loadedPlugins = new ConcurrentHashMap<>();

    /** 活跃的扩展点列表 (使用 CopyOnWriteArrayList 保证读多写少场景的线程安全) */
    private final List<RequestInterceptor> activeRequestInterceptors = new CopyOnWriteArrayList<>();
    private final List<ResponseInterceptor> activeResponseInterceptors = new CopyOnWriteArrayList<>();
    private final List<VariableFunctionExtension> activeVariableFunctions = new CopyOnWriteArrayList<>();
    private final List<MetricsCollectorExtension> activeMetricsCollectors = new CopyOnWriteArrayList<>();

    public PluginManager(@Value("${jcurl.plugin-dir}") String pluginDir) {
        this.pluginDir = Paths.get(pluginDir);
        this.compiler = ToolProvider.getSystemJavaCompiler();
        this.compilerAvailable = (compiler != null);
        if (!compilerAvailable) {
            log.warn("Java 编译器不可用,.java 源码插件将被跳开。.jar 插件仍可正常加载。"
                    + " 解决方法: 使用 JDK (而非 JRE) 启动,或使用 --add-modules jdk.compiler 参数。");
        }
        // compileClasspath 延迟构建: 只在有 .java 插件需要编译时才提取 fat jar classpath
        this.compileClasspath = null;
        // dataDir 取插件目录的父目录 (如 .api-client/), 供插件存放专属数据
        Path parent = this.pluginDir.toAbsolutePath().getParent();
        this.pluginContext = new SwingPluginContext(parent != null ? parent : this.pluginDir.toAbsolutePath());
    }

    /**
     * 构造器完成后异步加载所有插件。
     * <p>
     * 使用 daemon 线程执行加载,避免 fat jar classpath 提取等耗时操作阻塞 Swing UI 启动。
     */
    @PostConstruct
    public void init() {
        Thread pluginThread = new Thread(() -> {
            try {
                loadAll();
            } catch (Exception e) {
                log.error("插件系统初始化失败", e);
            }
        }, "plugin-loader");
        pluginThread.setDaemon(true);
        pluginThread.start();
    }

    /** 编译器是否可用 (JDK 环境可用,纯 JRE 环境不可用) */
    public boolean isCompilerAvailable() {
        return compilerAvailable;
    }

    /** 获取插件目录路径 */
    public Path getPluginDir() {
        return pluginDir;
    }

    /** 获取插件上下文 */
    public PluginContext getPluginContext() {
        return pluginContext;
    }

    // ==================== 加载 ====================

    /**
     * 扫描插件目录并加载所有 .java 和 .jar 插件。
     * 直接扫描 {@code pluginDir} 目录 (而非子目录)。
     */
    public void loadAll() {
        if (!Files.exists(pluginDir)) {
            try {
                Files.createDirectories(pluginDir);
            } catch (IOException e) {
                log.error("创建插件目录失败: {}", pluginDir, e);
            }
            return;
        }
        try (Stream<Path> files = Files.list(pluginDir)) {
            List<Path> allFiles = new ArrayList<>();
            files.forEach(allFiles::add);
            // 按文件名排序,保证加载顺序稳定
            allFiles.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (Path file : allFiles) {
                String fileName = file.getFileName().toString();
                try {
                    if (fileName.endsWith(".java")) {
                        loadJavaPlugin(file);
                    } else if (fileName.endsWith(".jar")) {
                        loadJarPlugin(file);
                    }
                } catch (Exception e) {
                    log.error("加载插件失败: {}", fileName, e);
                }
            }
        } catch (IOException e) {
            log.error("扫描插件目录失败: {}", pluginDir, e);
        }
        log.info("插件加载完成,共 {} 个", loadedPlugins.size());
    }

    /**
     * 加载单个 .java 源码插件: 读取源码 → 去除注释 → 内存编译 → 加载类 → 查找 ExtensionPoint 实现。
     *
     * @param javaFile .java 源文件路径
     */
    public void loadJavaPlugin(Path javaFile) {
        String pluginId = pluginIdFromJavaFile(javaFile);
        String filePath = javaFile.toString();

        if (!compilerAvailable) {
            log.warn("跳过 Java 源码插件 {} (编译器不可用)", javaFile.getFileName());
            loadedPlugins.put(pluginId, newFailedEntry(pluginId, filePath,
                    "编译器不可用,请使用 JDK 启动或改用 .jar 插件"));
            return;
        }

        PluginEntry entry;
        MemoryClassLoader classLoader = null;
        try {
            String sourceCode = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
            String cleanedCode = stripComments(sourceCode);
            classLoader = compileSource(pluginId, cleanedCode);

            List<String> classNames = classLoader.getClassNames();
            entry = buildPluginEntry(pluginId, filePath, classNames, classLoader);
        } catch (Exception e) {
            log.error("加载 .java 插件失败: {}", javaFile.getFileName(), e);
            entry = newFailedEntry(pluginId, filePath, e.getMessage());
            // MemoryClassLoader 仅持有内存字节码,无外部资源需关闭
        }
        loadedPlugins.put(pluginId, entry);
    }

    /**
     * 加载单个 .jar 插件: 使用 URLClassLoader 加载 JAR,扫描所有 .class 文件查找 ExtensionPoint 实现。
     * 不使用 ServiceLoader SPI 机制,改为直接类扫描,更灵活。
     *
     * @param jarFile .jar 文件路径
     */
    public void loadJarPlugin(Path jarFile) {
        String pluginId = pluginIdFromJarFile(jarFile);
        String filePath = jarFile.toString();

        PluginEntry entry;
        URLClassLoader classLoader = null;
        try {
            URL jarUrl = jarFile.toUri().toURL();
            classLoader = new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader());

            // 扫描 JAR 中所有 .class 文件
            List<String> classNames = new ArrayList<>();
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    String name = je.getName();
                    if (name.endsWith(".class")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        classNames.add(className);
                    }
                }
            }

            entry = buildPluginEntry(pluginId, filePath, classNames, classLoader);
        } catch (Exception e) {
            log.error("加载 .jar 插件失败: {}", jarFile.getFileName(), e);
            entry = newFailedEntry(pluginId, filePath, e.getMessage());
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        loadedPlugins.put(pluginId, entry);
    }

    // ==================== 卸载 ====================

    /**
     * 卸载指定插件: 取消注册扩展点、关闭 ClassLoader、从列表移除。
     *
     * @param pluginId 插件 ID
     */
    public void unloadPlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.remove(pluginId);
        if (entry == null) {
            log.warn("卸载插件失败: 插件不存在 {}", pluginId);
            return;
        }
        unregisterExtensionPoints(entry);
        closeClassLoader(pluginId, entry.classLoader);
        entry.status = "unloaded";
        entry.enabled = false;
        log.info("插件已卸载: {}", pluginId);
    }

    // ==================== 启用/禁用 ====================

    /**
     * 启用插件: 重新注册扩展点。
     *
     * @param pluginId 插件 ID
     */
    public void enablePlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null) {
            log.warn("启用插件失败: 插件不存在 {}", pluginId);
            return;
        }
        if (entry.plugin == null) {
            log.warn("启用插件失败: 插件未成功加载 {}", pluginId);
            return;
        }
        if (entry.enabled) {
            return;
        }
        entry.enabled = true;
        entry.status = "loaded";
        registerExtensionPoints(entry);
        log.info("插件已启用: {}", pluginId);
    }

    /**
     * 禁用插件: 取消注册扩展点但保留实例。
     *
     * @param pluginId 插件 ID
     */
    public void disablePlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null) {
            log.warn("禁用插件失败: 插件不存在 {}", pluginId);
            return;
        }
        if (!entry.enabled) {
            return;
        }
        entry.enabled = false;
        entry.status = "disabled";
        unregisterExtensionPoints(entry);
        log.info("插件已禁用: {}", pluginId);
    }

    // ==================== 重载 ====================

    /**
     * 重载指定插件 (先卸载再加载)。
     *
     * @param pluginId 插件 ID
     */
    public void reloadPlugin(String pluginId) throws Exception {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null || entry.filePath == null) {
            throw new IllegalArgumentException("插件不存在: " + pluginId);
        }
        String filePath = entry.filePath;
        unloadPlugin(pluginId);
        Path file = Paths.get(filePath);
        if (filePath.endsWith(".jar")) {
            loadJarPlugin(file);
        } else {
            loadJavaPlugin(file);
        }
        log.info("插件已重载: {}", pluginId);
    }

    /**
     * 重载所有插件。
     */
    public void reloadAll() {
        for (String pluginId : new ArrayList<>(loadedPlugins.keySet())) {
            try {
                reloadPlugin(pluginId);
            } catch (Exception e) {
                log.error("重载插件失败: {}", pluginId, e);
            }
        }
    }

    // ==================== 查询 ====================

    /**
     * 获取所有已加载插件信息。
     */
    public List<PluginInfo> getLoadedPlugins() {
        List<PluginInfo> result = new ArrayList<>();
        for (PluginEntry entry : loadedPlugins.values()) {
            PluginInfo info = new PluginInfo();
            info.setId(entry.pluginId);
            info.setFilePath(entry.filePath);
            info.setStatus(entry.status);
            info.setEnabled(entry.enabled);
            info.setExtensionPoints(new ArrayList<>(entry.extensionPoints));
            info.setErrorMessage(entry.errorMessage);
            if (entry.name != null) {
                info.setName(entry.name);
            } else {
                info.setName(entry.pluginId);
            }
            info.setVersion(entry.version != null ? entry.version : "");
            info.setDescription(entry.description != null ? entry.description : "");
            result.add(info);
        }
        return result;
    }

    // ==================== 扩展点应用 ====================

    /**
     * 应用所有活跃的请求拦截器。
     *
     * @param config 共享请求配置 (可修改)
     * @return 修改后的共享请求配置
     */
    public com.jcurl.plugin.model.dto.RequestConfig applyRequestInterceptors(com.jcurl.plugin.model.dto.RequestConfig config) {
        com.jcurl.plugin.model.dto.RequestConfig result = config;
        for (RequestInterceptor interceptor : activeRequestInterceptors) {
            try {
                result = interceptor.beforeRequest(result, pluginContext);
                if (result == null) {
                    log.warn("请求拦截器返回 null: {}", interceptor.getClass().getName());
                    break;
                }
            } catch (Exception e) {
                log.error("请求拦截器执行失败: {}", interceptor.getClass().getName(), e);
            }
        }
        return result;
    }

    /**
     * 应用所有活跃的响应拦截器。
     *
     * @param response 共享响应数据 (可修改)
     * @param config   原始共享请求配置 (只读)
     * @return 修改后的共享响应数据
     */
    public com.jcurl.plugin.model.dto.ResponseData applyResponseProcessors(
            com.jcurl.plugin.model.dto.ResponseData response, com.jcurl.plugin.model.dto.RequestConfig config) {
        com.jcurl.plugin.model.dto.ResponseData result = response;
        for (ResponseInterceptor interceptor : activeResponseInterceptors) {
            try {
                result = interceptor.afterResponse(result, config, pluginContext);
                if (result == null) {
                    log.warn("响应拦截器返回 null: {}", interceptor.getClass().getName());
                    break;
                }
            } catch (Exception e) {
                log.error("响应拦截器执行失败: {}", interceptor.getClass().getName(), e);
            }
        }
        return result;
    }

    /**
     * 获取所有活跃的变量函数扩展。
     */
    public List<VariableFunctionExtension> getVariableFunctions() {
        return new ArrayList<>(activeVariableFunctions);
    }

    /**
     * 应用所有活跃的指标采集器,采集自定义指标。
     * <p>
     * 合并所有采集器返回的指标,后注册的采集器覆盖同名指标。
     *
     * @param config   共享请求配置
     * @param response 共享响应数据
     * @return 指标名 → 指标值 的合并映射,无活跃采集器返回空 Map
     */
    public Map<String, Double> applyMetricsCollectors(
            com.jcurl.plugin.model.dto.RequestConfig config, com.jcurl.plugin.model.dto.ResponseData response) {
        Map<String, Double> metrics = new HashMap<>();
        for (MetricsCollectorExtension collector : activeMetricsCollectors) {
            try {
                Map<String, Double> collected = collector.collectMetrics(config, response, pluginContext);
                if (collected != null) {
                    metrics.putAll(collected);
                }
            } catch (Exception e) {
                log.error("指标采集器执行失败: {}", collector.getClass().getName(), e);
            }
        }
        return metrics;
    }

    /**
     * 获取所有活跃的指标采集器。
     */
    public List<MetricsCollectorExtension> getMetricsCollectors() {
        return new ArrayList<>(activeMetricsCollectors);
    }

    // ==================== 内部工具 ====================

    /**
     * 构建编译 classpath。
     * <p>
     * 包含两部分:
     * <ol>
     *   <li>{@code java.class.path} 系统属性中的路径(开发环境/普通 jar 环境)</li>
     *   <li>Spring Boot fat jar 提取出的 classpath(BOOT-INF/classes/ 和 BOOT-INF/lib/*.jar)</li>
     * </ol>
     * 在 fat jar 环境下 {@code java.class.path} 仅为 fat jar 自身路径,Java 编译器无法读取其内部
     * 的 {@code BOOT-INF/classes/} 与 {@code BOOT-INF/lib/*.jar},因此需要提取到缓存目录。
     */
    private String buildClasspath() {
        List<String> paths = new ArrayList<>();

        // 1. 从 java.class.path 获取
        String classpath = System.getProperty("java.class.path", "");
        if (classpath != null && !classpath.isEmpty()) {
            paths.add(classpath);
        }

        // 2. 检测 fat jar 并提取 classpath
        String fatJarClasspath = extractFatJarClasspath();
        if (fatJarClasspath != null && !fatJarClasspath.isEmpty()) {
            paths.add(fatJarClasspath);
        }

        if (paths.isEmpty()) {
            log.warn("java.class.path 为空,.java 插件编译可能失败");
            return "";
        }
        return String.join(File.pathSeparator, paths);
    }

    /**
     * 检测是否在 Spring Boot fat jar 中运行,如果是则提取 classpath。
     * <p>
     * Spring Boot fat jar 将应用类放在 BOOT-INF/classes/,依赖 jar 放在 BOOT-INF/lib/。
     * Java 编译器无法直接读取这些嵌套路径,需要提取到缓存目录。
     * <p>
     * 缓存目录: {@code ./.api-client/classpath-cache/}(当前工作目录下),
     * 按 fat jar 的最后修改时间 + 大小判断缓存有效性。首次提取较慢(3~4 秒),
     * 后续启动直接复用缓存,基本零开销。
     *
     * @return 提取后的 classpath 字符串(多个路径用路径分隔符连接),非 fat jar 环境返回 null
     */
    private String extractFatJarClasspath() {
        try {
            String jarPath = getJarLocation();
            if (jarPath == null) {
                return null;
            }
            if (!isFatJar(jarPath)) {
                return null;
            }

            // 缓存目录: ./.api-client/classpath-cache/ (相对于当前工作目录)
            Path cacheDir = Paths.get(".api-client", "classpath-cache");
            Path cacheMarker = cacheDir.resolve("cache.info");

            // 检查缓存是否有效 (fat jar 未修改则复用)
            File jarFile = new File(jarPath);
            long jarLastModified = jarFile.lastModified();
            long jarSize = jarFile.length();
            Path libDir = cacheDir.resolve("lib");
            if (Files.exists(cacheMarker) && Files.exists(libDir)) {
                try {
                    List<String> markerLines = Files.readAllLines(cacheMarker, StandardCharsets.UTF_8);
                    if (markerLines.size() >= 2) {
                        long cachedTime = Long.parseLong(markerLines.get(0).trim());
                        long cachedSize = Long.parseLong(markerLines.get(1).trim());
                        if (cachedTime == jarLastModified && cachedSize == jarSize) {
                            // 缓存有效,直接构建 classpath 字符串
                            List<String> entries = new ArrayList<>();
                            Path classesDir = cacheDir.resolve("classes");
                            if (Files.exists(classesDir)) {
                                entries.add(classesDir.toAbsolutePath().toString());
                            }
                            try (Stream<Path> jars = Files.list(libDir)) {
                                List<Path> jarList = new ArrayList<>();
                                jars.forEach(jarList::add);
                                for (Path p : jarList) {
                                    if (p.toString().endsWith(".jar")) {
                                        entries.add(p.toAbsolutePath().toString());
                                    }
                                }
                            }
                            log.info("fat jar classpath 缓存命中: {} 个条目", entries.size());
                            return String.join(File.pathSeparator, entries);
                        }
                    }
                } catch (Exception e) {
                    log.debug("缓存读取失败,将重新提取", e);
                }
            }

            // 缓存无效或不存在,重新提取
            log.info("检测到 Spring Boot fat jar,正在提取 classpath 供插件编译使用...");

            // 清理旧缓存
            if (Files.exists(cacheDir)) {
                try (Stream<Path> walk = Files.walk(cacheDir)) {
                    List<Path> paths = new ArrayList<>();
                    walk.forEach(paths::add);
                    // 倒序删除,先删文件再删目录
                    Collections.sort(paths, Collections.reverseOrder());
                    for (Path p : paths) {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // ignore
                        }
                    }
                }
            }
            Files.createDirectories(cacheDir);

            List<String> classpathEntries = new ArrayList<>();

            java.net.URI jarUri = java.net.URI.create("jar:" + jarFile.toURI());
            try (java.nio.file.FileSystem fs = FileSystems.newFileSystem(jarUri, new HashMap<String, String>())) {

                Path classesInJar = fs.getPath("BOOT-INF/classes");
                if (Files.exists(classesInJar)) {
                    Path classesDir = cacheDir.resolve("classes");
                    Files.createDirectories(classesDir);
                    copyDirectory(classesInJar, classesDir);
                    classpathEntries.add(classesDir.toAbsolutePath().toString());
                }

                Path libInJar = fs.getPath("BOOT-INF/lib");
                if (Files.exists(libInJar)) {
                    Path libDirTarget = cacheDir.resolve("lib");
                    Files.createDirectories(libDirTarget);
                    try (Stream<Path> jars = Files.list(libInJar)) {
                        List<Path> jarList = new ArrayList<>();
                        jars.forEach(jarList::add);
                        for (Path jar : jarList) {
                            if (!jar.toString().endsWith(".jar")) {
                                continue;
                            }
                            try {
                                String name = jar.getFileName().toString();
                                Path dest = libDirTarget.resolve(name);
                                Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING);
                                classpathEntries.add(dest.toAbsolutePath().toString());
                            } catch (IOException e) {
                                log.warn("提取依赖 jar 失败: {}", jar, e);
                            }
                        }
                    }
                }
            }

            // 写入缓存标记 (JDK8 没有 Files.writeString, 用 Files.write)
            String markerContent = jarLastModified + "\n" + jarSize;
            Files.write(cacheMarker, markerContent.getBytes(StandardCharsets.UTF_8));

            String result = String.join(File.pathSeparator, classpathEntries);
            log.info("fat jar classpath 提取完成: {} 个条目", classpathEntries.size());
            return result;

        } catch (Exception e) {
            log.warn("提取 fat jar classpath 失败,插件编译可能不可用", e);
            return null;
        }
    }

    /**
     * 获取当前 PluginManager 类所在 jar 文件路径(JDK 8 兼容)。
     * <p>
     * 优先使用 ProtectionDomain 获取,失败时返回 null。
     *
     * @return jar 文件绝对路径,非 jar 运行环境返回 null
     */
    private String getJarLocation() {
        try {
            // 方法1: ProtectionDomain
            java.security.CodeSource cs = PluginManager.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                java.net.URL location = cs.getLocation();
                if (location != null) {
                    String path = location.getPath();
                    path = java.net.URLDecoder.decode(path, "UTF-8");
                    if (path.startsWith("file:")) {
                        path = path.substring(5);
                    }
                    int bang = path.indexOf("!");
                    if (bang > 0) {
                        path = path.substring(0, bang);
                    }
                    // 修正 Windows 路径: /C:/xxx -> C:/xxx
                    if (path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':') {
                        path = path.substring(1);
                    }
                    if (path.endsWith(".jar")) {
                        return path;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取 jar 位置失败", e);
        }
        return null;
    }

    /**
     * 检查指定 jar 文件是否为 Spring Boot fat jar (包含 BOOT-INF 目录)。
     *
     * @param jarPath jar 文件路径
     * @return 是 fat jar 返回 true,否则 false
     */
    private boolean isFatJar(String jarPath) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jarPath)) {
            return zf.getEntry("BOOT-INF/classes/") != null
                || zf.getEntry("BOOT-INF/classes") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归复制目录(从源路径复制到目标路径,JDK 8 兼容)。
     *
     * @param source 源目录
     * @param target 目标目录
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            for (Path entry : entries) {
                Path dest = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(dest);
                } else {
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 从 .java 文件名生成插件 ID (去掉 .java 后缀) */
    private String pluginIdFromJavaFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - 5);
    }

    /** 从 .jar 文件名生成插件 ID (去掉 .jar 后缀) */
    private String pluginIdFromJarFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - 4);
    }

    /**
     * 构建插件条目: 在给定类名集合中查找实现 ExtensionPoint 的类,实例化并注册扩展点。
     * 插件元数据从 {@link JcurlPlugin} 注解读取。
     */
    private PluginEntry buildPluginEntry(String pluginId, String filePath,
                                         List<String> classNames, ClassLoader classLoader) {
        PluginEntry entry = new PluginEntry();
        entry.pluginId = pluginId;
        entry.filePath = filePath;
        entry.classLoader = classLoader;

        ExtensionPoint plugin = findExtensionInstance(classNames, classLoader, entry);
        if (plugin == null) {
            entry.status = "failed";
            entry.enabled = false;
            entry.errorMessage = "未找到实现 ExtensionPoint 接口的类";
            log.warn("插件 {} 未找到 ExtensionPoint 实现", pluginId);
            return entry;
        }

        entry.plugin = plugin;

        // 检查扩展点 (同一插件实例可实现多个扩展点)
        List<String> extPoints = new ArrayList<>();
        if (plugin instanceof RequestInterceptor) {
            entry.requestInterceptor = (RequestInterceptor) plugin;
            extPoints.add("RequestInterceptor");
        }
        if (plugin instanceof ResponseInterceptor) {
            entry.responseInterceptor = (ResponseInterceptor) plugin;
            extPoints.add("ResponseInterceptor");
        }
        if (plugin instanceof VariableFunctionExtension) {
            entry.variableFunction = (VariableFunctionExtension) plugin;
            extPoints.add("VariableFunctionExtension");
        }
        if (plugin instanceof MetricsCollectorExtension) {
            entry.metricsCollector = (MetricsCollectorExtension) plugin;
            extPoints.add("MetricsCollectorExtension");
        }
        entry.extensionPoints = extPoints;
        entry.enabled = true;
        entry.status = "loaded";

        registerExtensionPoints(entry);
        log.info("已加载插件: {} (扩展点: {})", pluginId, extPoints);
        return entry;
    }

    /**
     * 在类名集合中查找实现 ExtensionPoint 的类并实例化。
     * 优先检查顶层类 (不含 $),再检查内部类。
     * 同时从 {@link JcurlPlugin} 注解读取元数据填充到 entry。
     */
    private ExtensionPoint findExtensionInstance(List<String> classNames, ClassLoader classLoader, PluginEntry entry) {
        List<String> topLevel = new ArrayList<>();
        List<String> inner = new ArrayList<>();
        for (String name : classNames) {
            if (name.endsWith(".package-info") || name.endsWith(".module-info")) {
                continue;
            }
            if (name.contains("$")) {
                inner.add(name);
            } else {
                topLevel.add(name);
            }
        }
        List<String> ordered = new ArrayList<>(topLevel);
        ordered.addAll(inner);
        for (String className : ordered) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
                if (ExtensionPoint.class.isAssignableFrom(clazz)
                        && !clazz.isInterface()
                        && !Modifier.isAbstract(clazz.getModifiers())) {
                    Object instance = instantiate(clazz);
                    if (instance instanceof ExtensionPoint) {
                        // 读取 @JcurlPlugin 注解元数据
                        JcurlPlugin annotation = clazz.getAnnotation(JcurlPlugin.class);
                        if (annotation != null) {
                            entry.name = annotation.name().isEmpty() ? clazz.getSimpleName() : annotation.name();
                            entry.description = annotation.description();
                            entry.version = annotation.version();
                        } else {
                            entry.name = clazz.getSimpleName();
                        }
                        return (ExtensionPoint) instance;
                    }
                }
            } catch (Throwable e) {
                log.debug("跳过类 {} (无法加载或非插件): {}", className, e.toString());
            }
        }
        return null;
    }

    /**
     * 实例化插件类 (优先无参构造函数,其次带 PluginContext 参数的构造函数)。
     */
    private Object instantiate(Class<?> clazz) throws Exception {
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 0) {
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
        }
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == PluginContext.class) {
                ctor.setAccessible(true);
                return ctor.newInstance(pluginContext);
            }
        }
        log.warn("插件类 {} 没有合适的构造函数(无参或 PluginContext 参数)", clazz.getName());
        return null;
    }

    /** 注册扩展点到活跃列表 */
    private void registerExtensionPoints(PluginEntry entry) {
        if (entry.requestInterceptor != null) {
            activeRequestInterceptors.add(entry.requestInterceptor);
        }
        if (entry.responseInterceptor != null) {
            activeResponseInterceptors.add(entry.responseInterceptor);
        }
        if (entry.variableFunction != null) {
            activeVariableFunctions.add(entry.variableFunction);
        }
        if (entry.metricsCollector != null) {
            activeMetricsCollectors.add(entry.metricsCollector);
        }
    }

    /** 从活跃列表取消注册扩展点 */
    private void unregisterExtensionPoints(PluginEntry entry) {
        if (entry.requestInterceptor != null) {
            activeRequestInterceptors.remove(entry.requestInterceptor);
        }
        if (entry.responseInterceptor != null) {
            activeResponseInterceptors.remove(entry.responseInterceptor);
        }
        if (entry.variableFunction != null) {
            activeVariableFunctions.remove(entry.variableFunction);
        }
        if (entry.metricsCollector != null) {
            activeMetricsCollectors.remove(entry.metricsCollector);
        }
    }

    /** 关闭 ClassLoader (URLClassLoader 需显式关闭释放资源) */
    private void closeClassLoader(String pluginId, ClassLoader classLoader) {
        if (classLoader instanceof URLClassLoader) {
            try {
                ((URLClassLoader) classLoader).close();
            } catch (Exception e) {
                log.warn("关闭 ClassLoader 失败: {}", pluginId, e);
            }
        }
    }

    /** 创建一个加载失败的插件条目 */
    private PluginEntry newFailedEntry(String pluginId, String filePath, String errorMessage) {
        PluginEntry entry = new PluginEntry();
        entry.pluginId = pluginId;
        entry.filePath = filePath;
        entry.status = "failed";
        entry.enabled = false;
        entry.errorMessage = errorMessage;
        return entry;
    }

    /**
     * 编译 .java 源码 (去除注释后) 到内存,返回自定义 ClassLoader。
     *
     * @param pluginId   插件 ID (作为编译单元名)
     * @param sourceCode 已去除注释的源码
     * @return 包含编译后字节码的 MemoryClassLoader
     */
    private MemoryClassLoader compileSource(String pluginId, String sourceCode) throws IOException {
        // 懒加载 classpath: 只在第一次编译时提取 fat jar classpath
        if (compileClasspath == null) {
            synchronized (this) {
                if (compileClasspath == null) {
                    compileClasspath = buildClasspath();
                }
            }
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8);

        // 显式设置 classpath (从 java.class.path 构建),确保插件可引用宿主程序类
        List<File> cpFiles = new ArrayList<>();
        if (compileClasspath != null && !compileClasspath.isEmpty()) {
            for (String p : compileClasspath.split(File.pathSeparator)) {
                if (p != null && !p.isEmpty()) {
                    cpFiles.add(new File(p));
                }
            }
        }
        if (!cpFiles.isEmpty()) {
            stdFileManager.setLocation(StandardLocation.CLASS_PATH, cpFiles);
        }

        MemoryJavaFileManager fileManager = new MemoryJavaFileManager(stdFileManager);
        JavaFileObject source = new StringJavaFileObject(pluginId, sourceCode);

        // 同时通过 -classpath 选项传入,兼容各 JDK 版本
        List<String> options = new ArrayList<>();
        options.add("-classpath");
        options.add(compileClasspath != null ? compileClasspath : "");

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null,
                Collections.singletonList(source));

        boolean success = task.call();
        try {
            stdFileManager.close();
        } catch (Exception ignored) {
            // ignore
        }

        if (!success) {
            StringBuilder sb = new StringBuilder("编译失败:");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("\n  ").append(d.getKind())
                  .append(" (line ").append(d.getLineNumber()).append("): ")
                  .append(d.getMessage(null));
            }
            throw new IOException(sb.toString());
        }

        return new MemoryClassLoader(fileManager.getClassBytes(), getClass().getClassLoader());
    }

    /**
     * 去除 Java 源码中的注释 (单行 // 和多行 &#47;* *&#47; )。
     * <p>
     * 保留字符串/字符字面量内的注释标记,避免误删。
     * 编译时去除注释可避免注释中的特殊字符影响编译。
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

    // ==================== 内部类 ====================

    /** 插件条目 — 缓存插件实例、ClassLoader 及扩展点引用 */
    private static class PluginEntry {
        ExtensionPoint plugin;
        ClassLoader classLoader;
        String filePath;
        String pluginId;
        boolean enabled = true;
        List<String> extensionPoints = new ArrayList<>();
        String status = "loaded";
        String errorMessage;
        /** 插件元数据 (从 @JcurlPlugin 注解读取) */
        String name;
        String version;
        String description;
        /** 缓存实例用于 enable/disable 时重新注册 */
        RequestInterceptor requestInterceptor;
        ResponseInterceptor responseInterceptor;
        VariableFunctionExtension variableFunction;
        MetricsCollectorExtension metricsCollector;
    }

    /** 内存 ClassLoader — 从编译产物字节码中加载类,父加载器为宿主程序 ClassLoader */
    private static class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes;

        MemoryClassLoader(Map<String, byte[]> classBytes, ClassLoader parent) {
            super(parent);
            this.classBytes = classBytes;
        }

        /** 返回所有已编译类名 */
        List<String> getClassNames() {
            return new ArrayList<>(classBytes.keySet());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }

    /** 内存 JavaFileManager — 捕获编译器输出的字节码到 Map */
    private static class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, byte[]> classBytes = new HashMap<>();

        MemoryJavaFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        Map<String, byte[]> getClassBytes() {
            return classBytes;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            return new MemoryJavaFileObject(className, kind, classBytes);
        }
    }

    /** 源码 JavaFileObject — 从字符串读取源码 */
    private static class StringJavaFileObject extends SimpleJavaFileObject {
        private final String sourceCode;

        StringJavaFileObject(String className, String sourceCode) {
            super(URI.create("string:///" + className.replace('.', '/')
                    + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }
    }

    /** 输出 JavaFileObject — 将编译器输出字节捕获到 Map */
    private static class MemoryJavaFileObject extends SimpleJavaFileObject {
        private final String className;
        private final Map<String, byte[]> classBytes;

        MemoryJavaFileObject(String className, JavaFileObject.Kind kind, Map<String, byte[]> classBytes) {
            super(URI.create("string:///" + className.replace('.', '/') + kind.extension), kind);
            this.className = className;
            this.classBytes = classBytes;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    classBytes.put(className, toByteArray());
                    super.close();
                }
            };
        }
    }

    // ==================== 插件元数据类 ====================

    /**
     * 插件元数据 — 描述已加载插件的状态信息。
     * <p>
     * 状态值: "loaded" / "disabled" / "failed" / "unloaded"
     */
    public static class PluginInfo {
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
        /** 扩展点列表 (RequestInterceptor / ResponseInterceptor / VariableFunctionExtension / MetricsCollectorExtension) */
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
}
