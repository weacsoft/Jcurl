package com.jpostman.plugin;

import com.jpostman.model.dto.RequestConfig;
import com.jpostman.model.dto.ResponseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件管理器 — 负责插件的发现、加载、卸载和管理。
 * <p>
 * 使用 Java SPI (ServiceLoader) 机制发现插件。
 * 每个 JAR 插件使用独立的 URLClassLoader 实现类隔离。
 */
@Service
public class PluginManager {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Path pluginDir;

    /** 已加载的插件: pluginId -> PluginEntry */
    private final ConcurrentHashMap<String, PluginEntry> loadedPlugins = new ConcurrentHashMap<>();

    /** 请求拦截器列表 */
    private final List<RequestInterceptor> requestInterceptors = new ArrayList<>();
    /** 响应处理器列表 */
    private final List<ResponseProcessor> responseProcessors = new ArrayList<>();
    /** 变量函数列表 */
    private final List<VariableFunction> variableFunctions = new ArrayList<>();

    public PluginManager(@Value("${jpostman.plugin-dir}") String pluginDir) {
        this.pluginDir = Paths.get(pluginDir);
    }

    /** 内部类: 插件条目 */
    private static class PluginEntry {
        JPostmanPlugin plugin;
        URLClassLoader classLoader;
        String filePath;
    }

    /**
     * 扫描并加载 loaded/ 目录下的所有 JAR 插件。
     */
    public void loadAll() {
        File loadedDir = pluginDir.resolve("loaded").toFile();
        if (!loadedDir.exists()) {
            loadedDir.mkdirs();
            return;
        }
        File[] jarFiles = loadedDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null) return;
        for (File jar : jarFiles) {
            try {
                loadPlugin(jar);
            } catch (Exception e) {
                log.error("加载插件失败: " + jar.getName(), e);
            }
        }
    }

    /**
     * 加载单个 JAR 插件。
     */
    public void loadPlugin(File jarFile) throws Exception {
        URL jarUrl = jarFile.toURI().toURL();
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader());

        ServiceLoader<JPostmanPlugin> serviceLoader = ServiceLoader.load(JPostmanPlugin.class, classLoader);
        for (JPostmanPlugin plugin : serviceLoader) {
            PluginEntry entry = new PluginEntry();
            entry.plugin = plugin;
            entry.classLoader = classLoader;
            entry.filePath = jarFile.getAbsolutePath();

            plugin.onLoad();

            String pluginId = plugin.getName() + "-" + plugin.getVersion();
            loadedPlugins.put(pluginId, entry);
            log.info("已加载插件: {} v{}", plugin.getName(), plugin.getVersion());

            // 注册扩展点
            if (plugin instanceof RequestInterceptor) {
                requestInterceptors.add((RequestInterceptor) plugin);
            }
            if (plugin instanceof ResponseProcessor) {
                responseProcessors.add((ResponseProcessor) plugin);
            }
            if (plugin instanceof VariableFunction) {
                variableFunctions.add((VariableFunction) plugin);
            }
        }
    }

    /**
     * 卸载插件。
     */
    public void unloadPlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.remove(pluginId);
        if (entry != null) {
            try {
                entry.plugin.onUnload();
                entry.classLoader.close();
                log.info("已卸载插件: {}", pluginId);
            } catch (Exception e) {
                log.error("卸载插件失败: " + pluginId, e);
            }
        }
    }

    /**
     * 获取所有已加载插件信息。
     */
    public List<PluginInfo> getLoadedPlugins() {
        List<PluginInfo> result = new ArrayList<>();
        for (PluginEntry entry : loadedPlugins.values()) {
            PluginInfo info = new PluginInfo(
                entry.plugin.getName(),
                entry.plugin.getVersion(),
                entry.plugin.getDescription(),
                entry.filePath,
                "loaded"
            );
            result.add(info);
        }
        return result;
    }

    /**
     * 应用请求拦截器。
     */
    public RequestConfig applyRequestInterceptors(RequestConfig config) {
        RequestConfig result = config;
        for (RequestInterceptor interceptor : requestInterceptors) {
            try {
                result = interceptor.intercept(result);
            } catch (Exception e) {
                log.error("请求拦截器执行失败", e);
            }
        }
        return result;
    }

    /**
     * 应用响应处理器。
     */
    public ResponseData applyResponseProcessors(ResponseData response) {
        ResponseData result = response;
        for (ResponseProcessor processor : responseProcessors) {
            try {
                result = processor.process(result);
            } catch (Exception e) {
                log.error("响应处理器执行失败", e);
            }
        }
        return result;
    }

    /**
     * 获取所有变量函数。
     */
    public List<VariableFunction> getVariableFunctions() {
        return new ArrayList<>(variableFunctions);
    }
}
