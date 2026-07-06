package com.jcurl.plugin;

import com.jcurl.model.dto.RequestConfig;
import com.jcurl.model.dto.ResponseData;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * 支持两种插件形式:
 * <ul>
 *   <li><b>.java 源码插件</b>: 使用 {@link JavaCompiler} 在内存中编译 (需 JDK 环境),
 *       去除注释后编译,通过自定义 {@link MemoryClassLoader} 加载</li>
 *   <li><b>.jar 插件</b>: 使用 {@link URLClassLoader} 加载,扫描 JAR 中所有 .class 文件
 *       查找实现 {@link JcurlPlugin} 接口的类 (不依赖 ServiceLoader SPI)</li>
 * </ul>
 * <p>
 * 扩展点: {@link RequestInterceptor}、{@link ResponseProcessor}、{@link VariableFunction}。
 * 插件类实现 {@link JcurlPlugin} 接口,可同时实现一个或多个扩展点接口。
 * <p>
 * 插件 ID = 文件名去掉后缀 (.java 或 .jar)。
 */
@Service
public class PluginManager {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Path pluginDir;
    private final JavaCompiler compiler;
    private final boolean compilerAvailable;
    private final String compileClasspath;

    /** 插件 ID → PluginEntry */
    private final ConcurrentHashMap<String, PluginEntry> loadedPlugins = new ConcurrentHashMap<>();

    /** 活跃的扩展点列表 (使用 CopyOnWriteArrayList 保证读多写少场景的线程安全) */
    private final List<RequestInterceptor> activeRequestInterceptors = new CopyOnWriteArrayList<>();
    private final List<ResponseProcessor> activeResponseProcessors = new CopyOnWriteArrayList<>();
    private final List<VariableFunction> activeVariableFunctions = new CopyOnWriteArrayList<>();

    public PluginManager(@Value("${jcurl.plugin-dir}") String pluginDir) {
        this.pluginDir = Paths.get(pluginDir);
        this.compiler = ToolProvider.getSystemJavaCompiler();
        this.compilerAvailable = (compiler != null);
        if (!compilerAvailable) {
            log.warn("Java 编译器不可用,.java 源码插件将被跳过。.jar 插件仍可正常加载。");
        }
        this.compileClasspath = buildClasspath();
    }

    /** 构造器完成后自动加载所有插件 */
    @PostConstruct
    public void init() {
        loadAll();
    }

    /** 编译器是否可用 (JDK 环境可用,纯 JRE 环境不可用) */
    public boolean isCompilerAvailable() {
        return compilerAvailable;
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
     * 加载单个 .java 源码插件: 读取源码 → 去除注释 → 内存编译 → 加载类 → 查找 JcurlPlugin 实现。
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
     * 加载单个 .jar 插件: 使用 URLClassLoader 加载 JAR,扫描所有 .class 文件查找 JcurlPlugin 实现。
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
     * 卸载指定插件: 取消注册扩展点、调用 onUnload、关闭 ClassLoader、从列表移除。
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
        if (entry.plugin != null) {
            try {
                entry.plugin.onUnload();
            } catch (Exception e) {
                log.error("插件 onUnload 失败: {}", pluginId, e);
            }
        }
        closeClassLoader(pluginId, entry.classLoader);
        entry.status = "unloaded";
        entry.enabled = false;
        log.info("插件已卸载: {}", pluginId);
    }

    // ==================== 启用/禁用 ====================

    /**
     * 启用插件: 重新注册扩展点 (不重新调用 onLoad)。
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
     * 禁用插件: 取消注册扩展点但保留实例 (不调用 onUnload)。
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
            if (entry.plugin != null) {
                info.setName(entry.plugin.getName());
                info.setVersion(entry.plugin.getVersion());
                info.setDescription(entry.plugin.getDescription());
            } else {
                info.setName(entry.pluginId);
                info.setVersion("");
                info.setDescription("");
            }
            result.add(info);
        }
        return result;
    }

    // ==================== 扩展点应用 ====================

    /**
     * 应用所有活跃的请求拦截器。
     */
    public RequestConfig applyRequestInterceptors(RequestConfig config) {
        RequestConfig result = config;
        for (RequestInterceptor interceptor : activeRequestInterceptors) {
            try {
                result = interceptor.intercept(result);
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
     * 应用所有活跃的响应处理器。
     */
    public ResponseData applyResponseProcessors(ResponseData response) {
        ResponseData result = response;
        for (ResponseProcessor processor : activeResponseProcessors) {
            try {
                result = processor.process(result);
                if (result == null) {
                    log.warn("响应处理器返回 null: {}", processor.getClass().getName());
                    break;
                }
            } catch (Exception e) {
                log.error("响应处理器执行失败: {}", processor.getClass().getName(), e);
            }
        }
        return result;
    }

    /**
     * 获取所有活跃的变量函数。
     */
    public List<VariableFunction> getVariableFunctions() {
        return new ArrayList<>(activeVariableFunctions);
    }

    // ==================== 内部工具 ====================

    /** 从 java.class.path 系统属性构建编译 classpath */
    private String buildClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath == null || classpath.isEmpty()) {
            log.warn("java.class.path 为空,.java 插件编译可能失败");
            return "";
        }
        return classpath;
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
     * 构建插件条目: 在给定类名集合中查找实现 JcurlPlugin 的类,实例化并注册扩展点。
     */
    private PluginEntry buildPluginEntry(String pluginId, String filePath,
                                         List<String> classNames, ClassLoader classLoader) {
        PluginEntry entry = new PluginEntry();
        entry.pluginId = pluginId;
        entry.filePath = filePath;
        entry.classLoader = classLoader;

        JcurlPlugin plugin = findPluginInstance(classNames, classLoader);
        if (plugin == null) {
            entry.status = "failed";
            entry.enabled = false;
            entry.errorMessage = "未找到实现 JcurlPlugin 接口的类";
            log.warn("插件 {} 未找到 JcurlPlugin 实现", pluginId);
            return entry;
        }

        entry.plugin = plugin;
        try {
            plugin.onLoad();
        } catch (Exception e) {
            log.error("插件 onLoad 失败: {}", pluginId, e);
            entry.status = "failed";
            entry.enabled = false;
            entry.errorMessage = "onLoad 失败: " + e.getMessage();
            return entry;
        }

        // 检查扩展点 (同一插件实例可实现多个扩展点)
        List<String> extPoints = new ArrayList<>();
        if (plugin instanceof RequestInterceptor) {
            entry.requestInterceptor = (RequestInterceptor) plugin;
            extPoints.add("RequestInterceptor");
        }
        if (plugin instanceof ResponseProcessor) {
            entry.responseProcessor = (ResponseProcessor) plugin;
            extPoints.add("ResponseProcessor");
        }
        if (plugin instanceof VariableFunction) {
            entry.variableFunction = (VariableFunction) plugin;
            extPoints.add("VariableFunction");
        }
        entry.extensionPoints = extPoints;
        entry.enabled = true;
        entry.status = "loaded";

        registerExtensionPoints(entry);
        log.info("已加载插件: {} (扩展点: {})", pluginId, extPoints);
        return entry;
    }

    /**
     * 在类名集合中查找实现 JcurlPlugin 接口的类并实例化。
     * 优先检查顶层类 (不含 $),再检查内部类。
     */
    private JcurlPlugin findPluginInstance(List<String> classNames, ClassLoader classLoader) {
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
                if (JcurlPlugin.class.isAssignableFrom(clazz)
                        && !clazz.isInterface()
                        && !Modifier.isAbstract(clazz.getModifiers())) {
                    Object instance = instantiate(clazz);
                    if (instance instanceof JcurlPlugin) {
                        return (JcurlPlugin) instance;
                    }
                }
            } catch (Throwable e) {
                log.debug("跳过类 {} (无法加载或非插件): {}", className, e.toString());
            }
        }
        return null;
    }

    /**
     * 实例化插件类 (优先无参构造函数)。
     */
    private Object instantiate(Class<?> clazz) throws Exception {
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 0) {
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
        }
        log.warn("插件类 {} 没有无参构造函数", clazz.getName());
        return null;
    }

    /** 注册扩展点到活跃列表 */
    private void registerExtensionPoints(PluginEntry entry) {
        if (entry.requestInterceptor != null) {
            activeRequestInterceptors.add(entry.requestInterceptor);
        }
        if (entry.responseProcessor != null) {
            activeResponseProcessors.add(entry.responseProcessor);
        }
        if (entry.variableFunction != null) {
            activeVariableFunctions.add(entry.variableFunction);
        }
    }

    /** 从活跃列表取消注册扩展点 */
    private void unregisterExtensionPoints(PluginEntry entry) {
        if (entry.requestInterceptor != null) {
            activeRequestInterceptors.remove(entry.requestInterceptor);
        }
        if (entry.responseProcessor != null) {
            activeResponseProcessors.remove(entry.responseProcessor);
        }
        if (entry.variableFunction != null) {
            activeVariableFunctions.remove(entry.variableFunction);
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
        JcurlPlugin plugin;
        ClassLoader classLoader;
        String filePath;
        String pluginId;
        boolean enabled = true;
        List<String> extensionPoints = new ArrayList<>();
        String status = "loaded";
        String errorMessage;
        /** 缓存实例用于 enable/disable 时重新注册 */
        RequestInterceptor requestInterceptor;
        ResponseProcessor responseProcessor;
        VariableFunction variableFunction;
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
}
