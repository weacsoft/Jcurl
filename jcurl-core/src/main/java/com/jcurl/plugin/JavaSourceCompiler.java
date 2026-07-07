package com.jcurl.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java 源文件运行时编译器 — 使用 JDK 内置的 javax.tools.JavaCompiler 编译 .java 文件。
 * <p>
 * 编译流程:
 * <ol>
 *   <li>读取 .java 源文件内容</li>
 *   <li>构建编译任务(包含当前 classpath,使插件可访问宿主类)</li>
 *   <li>编译到内存中(自定义 JavaFileManager,不产生磁盘 .class 文件)</li>
 *   <li>通过自定义 ClassLoader 加载编译后的字节码</li>
 * </ol>
 * <p>
 * 插件源文件可引用宿主程序的所有公共类(ExtensionPoint 接口、Model 类、Service 类等)。
 */
public class JavaSourceCompiler {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceCompiler.class);

    private final JavaCompiler compiler;
    private volatile List<String> compileOptions;
    private final boolean available;

    public JavaSourceCompiler() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (this.compiler == null) {
            log.warn("JDK JavaCompiler 不可用(可能在 JRE 或 fat jar 环境下运行)。插件编译功能将被禁用。"
                    + " 解决方法: 使用 java --add-modules jdk.compiler -jar xxx.jar 启动,或确保 JDK 环境变量正确。");
            this.available = false;
            this.compileOptions = List.of();
        } else {
            this.available = true;
            // 延迟构建 compileOptions: 只在有 .java 插件需要编译时才提取 fat jar classpath
            this.compileOptions = null;
        }
    }

    /** 插件编译器是否可用 */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 懒加载编译选项。只在第一次实际编译时才提取 fat jar classpath,
     * 避免在没有 .java 插件时浪费 3-4 秒提取 classpath。
     */
    private List<String> getCompileOptions() {
        if (compileOptions == null) {
            synchronized (this) {
                if (compileOptions == null) {
                    compileOptions = buildCompileOptions();
                }
            }
        }
        return compileOptions;
    }

    /**
     * 编译一个或多个 Java 源文件,返回可加载的 ClassLoader。
     *
     * @param sources 源文件名 → 源代码内容 的映射
     * @return 包含编译后类的 ClassLoader
     * @throws CompilationException 编译失败时抛出
     */
    public ClassLoader compile(Map<String, String> sources) throws CompilationException {
        if (!available) {
            throw new CompilationException("JDK JavaCompiler 不可用,无法编译插件。请使用 --add-modules jdk.compiler 启动,或确保在 JDK 环境下运行。");
        }
        List<JavaSourceFromString> sourceObjects = sources.entrySet().stream()
                .map(e -> new JavaSourceFromString(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        InMemoryFileManager fileManager = new InMemoryFileManager(
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8));

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, getCompileOptions(), null, sourceObjects);

        boolean success = task.call();

        if (!success) {
            String errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> "行 " + d.getLineNumber() + ": " + d.getMessage(null))
                    .collect(Collectors.joining("\n"));
            throw new CompilationException("编译失败:\n" + errors);
        }

        // 创建 ClassLoader,父 ClassLoader 为当前线程的 ContextClassLoader
        // 这样插件可以访问宿主类
        return new InMemoryClassLoader(fileManager.getClassBytes(),
                Thread.currentThread().getContextClassLoader());
    }

    /** 构建编译选项:包含当前 classpath */
    private List<String> buildCompileOptions() {
        List<String> options = new ArrayList<>();
        options.add("-source");
        options.add("17");
        options.add("-target");
        options.add("17");
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-classpath");
        options.add(buildClasspath());
        return options;
    }

    /** 构建编译 classpath(包含当前运行时的所有 classpath 条目) */
    private String buildClasspath() {
        List<String> paths = new ArrayList<>();

        // 1. 尝试从 URLClassLoader 获取
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl instanceof URLClassLoader urlCl) {
            for (URL url : urlCl.getURLs()) {
                paths.add(url.getFile());
            }
        }

        // 2. 从 java.class.path 获取
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            paths.addAll(Arrays.asList(classPath.split(File.pathSeparator)));
        }

        // 3. 检测 Spring Boot fat jar 并提取 classpath
        String fatJarClasspath = extractFatJarClasspath();
        if (fatJarClasspath != null) {
            paths.add(fatJarClasspath);
        }

        return String.join(File.pathSeparator, paths);
    }

    /**
     * 检测是否在 Spring Boot fat jar 中运行,如果是则提取 classpath。
     * <p>
     * Spring Boot fat jar 将应用类放在 BOOT-INF/classes/,依赖 jar 放在 BOOT-INF/lib/。
     * Java 编译器无法直接读取这些嵌套路径,需要提取到临时目录。
     *
     * @return 提取后的 classpath 字符串(多个路径用路径分隔符连接),非 fat jar 环境返回 null
     */
    private String extractFatJarClasspath() {
        try {
            String jarPath = getJarLocation();
            if (jarPath == null) return null;
            if (!isFatJar(jarPath)) return null;

            // 缓存目录: .api-client/classpath-cache/ (当前工作目录下)
            Path cacheDir = Path.of(System.getProperty("user.dir"), ".api-client", "classpath-cache");
            Path cacheMarker = cacheDir.resolve("cache.info");

            // 检查缓存是否有效 (fat jar 未修改则复用)
            long jarLastModified = new File(jarPath).lastModified();
            long jarSize = new File(jarPath).length();
            if (Files.exists(cacheMarker) && Files.exists(cacheDir.resolve("lib"))) {
                try {
                    List<String> markerLines = Files.readAllLines(cacheMarker);
                    if (markerLines.size() >= 2) {
                        long cachedTime = Long.parseLong(markerLines.get(0));
                        long cachedSize = Long.parseLong(markerLines.get(1));
                        if (cachedTime == jarLastModified && cachedSize == jarSize) {
                            // 缓存有效,直接构建 classpath 字符串
                            List<String> entries = new ArrayList<>();
                            Path classesDir = cacheDir.resolve("classes");
                            if (Files.exists(classesDir)) entries.add(classesDir.toAbsolutePath().toString());
                            Path libDir = cacheDir.resolve("lib");
                            try (Stream<Path> jars = Files.list(libDir)) {
                                jars.filter(p -> p.toString().endsWith(".jar"))
                                        .forEach(p -> entries.add(p.toAbsolutePath().toString()));
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
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
            }
            Files.createDirectories(cacheDir);

            List<String> classpathEntries = new ArrayList<>();

            URI jarUri = URI.create("jar:" + new File(jarPath).toURI());
            try (java.nio.file.FileSystem fs = FileSystems.newFileSystem(jarUri, Map.of())) {

                Path classesInJar = fs.getPath("BOOT-INF/classes");
                if (Files.exists(classesInJar)) {
                    Path classesDir = cacheDir.resolve("classes");
                    Files.createDirectories(classesDir);
                    copyDirectory(classesInJar, classesDir);
                    classpathEntries.add(classesDir.toAbsolutePath().toString());
                }

                Path libInJar = fs.getPath("BOOT-INF/lib");
                if (Files.exists(libInJar)) {
                    Path libDir = cacheDir.resolve("lib");
                    Files.createDirectories(libDir);
                    try (Stream<Path> jars = Files.list(libInJar)) {
                        jars.filter(p -> p.toString().endsWith(".jar"))
                                .forEach(jar -> {
                                    try {
                                        String name = jar.getFileName().toString();
                                        Path dest = libDir.resolve(name);
                                        Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING);
                                        classpathEntries.add(dest.toAbsolutePath().toString());
                                    } catch (IOException e) {
                                        log.warn("提取依赖 jar 失败: {}", jar, e);
                                    }
                                });
                    }
                }
            }

            // 写入缓存标记
            Files.writeString(cacheMarker, jarLastModified + "\n" + jarSize);

            String result = String.join(File.pathSeparator, classpathEntries);
            log.info("fat jar classpath 提取完成: {} 个条目", classpathEntries.size());
            return result;

        } catch (Exception e) {
            log.warn("提取 fat jar classpath 失败,插件编译可能不可用", e);
            return null;
        }
    }

    /** 获取当前类所在的 jar 文件路径 */
    private String getJarLocation() {
        // 方法1: 通过 ProtectionDomain 获取
        try {
            URL location = JavaSourceCompiler.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                String path = parseJarPath(location);
                if (path != null) return path;
            }
        } catch (Exception e) {
            log.debug("ProtectionDomain 方式获取 jar 路径失败", e);
        }

        // 方法2: 通过 java.class.path 获取( fat jar 运行时包含 jar 路径)
        try {
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && !classPath.isBlank()) {
                for (String entry : classPath.split(File.pathSeparator)) {
                    if (entry.endsWith(".jar") && isFatJar(entry)) {
                        return entry;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("java.class.path 方式获取 jar 路径失败", e);
        }

        // 方法3: 通过 classloader URL 获取
        try {
            ClassLoader cl = JavaSourceCompiler.class.getClassLoader();
            if (cl instanceof URLClassLoader urlCl) {
                for (URL url : urlCl.getURLs()) {
                    String path = parseJarPath(url);
                    if (path != null && isFatJar(path)) {
                        return path;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("ClassLoader 方式获取 jar 路径失败", e);
        }

        return null;
    }

    /** 从 URL 解析 jar 文件路径 */
    private String parseJarPath(URL url) {
        try {
            String path = url.getPath();
            path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
            if (path.startsWith("file:")) {
                path = path.substring(5);
            }
            int bang = path.indexOf("!");
            if (bang > 0) {
                path = path.substring(0, bang);
            }
            if (path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            return path.endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 检查 jar 文件是否是 Spring Boot fat jar(包含 BOOT-INF 目录) */
    private boolean isFatJar(String jarPath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath)) {
            return zip.getEntry("BOOT-INF/classes/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 递归复制目录 */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path entry : stream.toList()) {
                Path dest = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(entry, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ==================== 编译异常 ====================

    public static class CompilationException extends Exception {
        public CompilationException(String message) {
            super(message);
        }
    }

    // ==================== 内存文件管理 ====================

    /** 从字符串创建的 Java 源文件对象 */
    private static class JavaSourceFromString extends SimpleJavaFileObject {
        private final String code;

        JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** 内存 JavaFileManager — 将编译后的 .class 存储在内存中 */
    private static class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, byte[]> classBytes = new HashMap<>();

        InMemoryFileManager(StandardJavaFileManager standardManager) {
            super(standardManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            if (kind == JavaFileObject.Kind.CLASS) {
                return new InMemoryClassFile(className, kind, classBytes);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        Map<String, byte[]> getClassBytes() {
            return classBytes;
        }

        /** 内存中的 .class 文件对象(静态内部类,通过 classBytes 引用共享 map) */
        private static class InMemoryClassFile extends SimpleJavaFileObject {
            private final String className;
            private final Map<String, byte[]> classBytes;

            InMemoryClassFile(String className, Kind kind, Map<String, byte[]> classBytes) {
                super(URI.create("memory:///" + className.replace('.', '/') + ".class"), kind);
                this.className = className;
                this.classBytes = classBytes;
            }

            @Override
            public java.io.OutputStream openOutputStream() {
                return new java.io.ByteArrayOutputStream() {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        classBytes.put(className, toByteArray());
                    }
                };
            }
        }
    }

    /** 内存 ClassLoader — 从内存中的字节码加载类 */
    private static class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes;

        InMemoryClassLoader(Map<String, byte[]> classBytes, ClassLoader parent) {
            super(parent);
            this.classBytes = classBytes;
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
}
