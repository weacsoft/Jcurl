package com.jpostman2.plugin;

import com.jpostman2.config.AppProperties;
import com.jpostman2.model.dto.RequestConfig;
import com.jpostman2.model.dto.ResponseData;
import com.jpostman2.plugin.extension.MetricsCollectorExtension;
import com.jpostman2.plugin.extension.RequestExecutorExtension;
import com.jpostman2.plugin.extension.RequestInterceptor;
import com.jpostman2.plugin.extension.ResponseInterceptor;
import com.jpostman2.plugin.extension.VariableFunctionExtension;
import com.jpostman2.service.CollectionService;
import com.jpostman2.service.EnvironmentService;
import com.jpostman2.service.HistoryService;
import com.jpostman2.service.VariableResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件服务 — Spring 集成层,封装 {@link PluginManager} 并提供宿主程序的扩展点调用入口。
 * <p>
 * 职责:
 * <ul>
 *   <li>在应用启动时({@code @PostConstruct})初始化 PluginManager 并加载所有插件</li>
 *   <li>提供请求拦截器调用入口:{@link #beforeRequest} / {@link #afterResponse}</li>
 *   <li>提供自定义变量函数查询:{@link #resolveCustomFunction}</li>
 *   <li>提供自定义请求执行器查询:{@link #findRequestExecutor}</li>
 *   <li>提供指标采集器调用入口:{@link #collectMetrics}</li>
 *   <li>提供插件管理操作(加载/卸载/重载/启用/禁用)</li>
 * </ul>
 */
@Service
public class PluginService {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    private final AppProperties appProperties;
    private final CollectionService collectionService;
    private final EnvironmentService environmentService;
    private final HistoryService historyService;
    private final VariableResolver variableResolver;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private PluginManager pluginManager;
    private PluginContext pluginContext;
    private Path pluginsDir;

    public PluginService(AppProperties appProperties,
                         CollectionService collectionService,
                         EnvironmentService environmentService,
                         HistoryService historyService,
                         VariableResolver variableResolver,
                         com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.collectionService = collectionService;
        this.environmentService = environmentService;
        this.historyService = historyService;
        this.variableResolver = variableResolver;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        Path dataDir = resolveDataDir();
        this.pluginsDir = dataDir.resolve("plugins");

        pluginContext = new PluginContextImpl(
                dataDir, objectMapper,
                collectionService, environmentService,
                historyService, variableResolver);

        pluginManager = new PluginManager(pluginsDir, pluginContext);

        if (!pluginManager.isCompilerAvailable()) {
            log.warn("插件编译器不可用,插件功能已禁用。如需启用插件,请使用 JDK 启动: java --add-modules jdk.compiler -jar xxx.jar");
            return;
        }

        try {
            int count = pluginManager.loadAll();
            log.info("插件系统初始化完成: {} 个插件已加载", count);
        } catch (Exception e) {
            log.error("插件系统初始化失败", e);
        }
    }

    // ==================== 请求拦截 ====================

    /**
     * 请求发送前调用所有 RequestInterceptor。
     *
     * @param config 请求配置(会被拦截器修改)
     * @return 修改后的请求配置
     */
    public RequestConfig beforeRequest(RequestConfig config) {
        List<RequestInterceptor> interceptors = pluginManager.getRegistry()
                .getExtensions(RequestInterceptor.class);
        for (RequestInterceptor interceptor : interceptors) {
            try {
                config = interceptor.beforeRequest(config, pluginContext);
            } catch (Exception e) {
                log.error("请求拦截器执行失败: {}", interceptor.getClass().getName(), e);
            }
        }
        return config;
    }

    /**
     * 响应接收后调用所有 ResponseInterceptor。
     *
     * @param response 响应数据(会被拦截器修改)
     * @param config   原始请求配置
     * @return 修改后的响应数据
     */
    public ResponseData afterResponse(ResponseData response, RequestConfig config) {
        List<ResponseInterceptor> interceptors = pluginManager.getRegistry()
                .getExtensions(ResponseInterceptor.class);
        for (ResponseInterceptor interceptor : interceptors) {
            try {
                response = interceptor.afterResponse(response, config, pluginContext);
            } catch (Exception e) {
                log.error("响应拦截器执行失败: {}", interceptor.getClass().getName(), e);
            }
        }
        return response;
    }

    // ==================== 变量函数扩展 ====================

    /**
     * 解析自定义变量函数。
     *
     * @param functionName 函数名(不含 {{$}})
     * @param args         参数
     * @return 函数结果,null 表示无插件支持该函数
     */
    public String resolveCustomFunction(String functionName, String args) {
        List<VariableFunctionExtension> extensions = pluginManager.getRegistry()
                .getExtensions(VariableFunctionExtension.class);
        for (VariableFunctionExtension ext : extensions) {
            try {
                List<String> names = ext.getFunctionNames(pluginContext);
                if (names != null && names.contains(functionName)) {
                    String result = ext.executeFunction(functionName, args, pluginContext);
                    if (result != null) {
                        return result;
                    }
                }
            } catch (Exception e) {
                log.error("变量函数扩展执行失败: {}", ext.getClass().getName(), e);
            }
        }
        return null;
    }

    // ==================== 请求执行器扩展 ====================

    /**
     * 查找支持该请求的自定义执行器。
     *
     * @param config 请求配置
     * @return 支持且优先级最高的执行器,无则返回 null
     */
    public RequestExecutorExtension findRequestExecutor(RequestConfig config) {
        List<RequestExecutorExtension> executors = pluginManager.getRegistry()
                .getExtensions(RequestExecutorExtension.class);
        RequestExecutorExtension best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (RequestExecutorExtension executor : executors) {
            try {
                if (executor.supports(config, pluginContext) && executor.getPriority() > bestPriority) {
                    best = executor;
                    bestPriority = executor.getPriority();
                }
            } catch (Exception e) {
                log.error("请求执行器查询失败: {}", executor.getClass().getName(), e);
            }
        }
        return best;
    }

    // ==================== 指标采集扩展 ====================

    /**
     * 采集自定义指标。
     *
     * @param config   请求配置
     * @param response 响应数据
     * @return 指标名 → 值 的映射
     */
    public Map<String, Double> collectMetrics(RequestConfig config, ResponseData response) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        List<MetricsCollectorExtension> collectors = pluginManager.getRegistry()
                .getExtensions(MetricsCollectorExtension.class);
        for (MetricsCollectorExtension collector : collectors) {
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

    // ==================== 插件管理操作 ====================

    /** 获取所有插件列表 */
    public List<Plugin> listPlugins() {
        return pluginManager.listPlugins();
    }

    /** 重载所有插件 */
    public int reloadAll() {
        return pluginManager.reloadAll();
    }

    /** 重载指定插件 */
    public void reloadPlugin(String pluginId) throws Exception {
        pluginManager.reloadPlugin(pluginId);
    }

    /** 启用插件 */
    public void enablePlugin(String pluginId) {
        pluginManager.enablePlugin(pluginId);
    }

    /** 禁用插件 */
    public void disablePlugin(String pluginId) {
        pluginManager.disablePlugin(pluginId);
    }

    /**
     * 安装新插件: 将源 .java 文件复制到插件目录并加载。
     *
     * @param sourceFile 用户选择的插件源文件 (.java)
     * @return 加载后的插件元数据; 加载失败时返回 status=FAILED 的 Plugin
     */
    public Plugin installPlugin(Path sourceFile) {
        try {
            Files.createDirectories(pluginsDir);
            String fileName = sourceFile.getFileName().toString();
            if (!fileName.endsWith(".java")) {
                Plugin fail = new Plugin(fileName, fileName, sourceFile.toString());
                fail.setStatus(Plugin.LoadStatus.FAILED);
                fail.setErrorMessage("仅支持 .java 源码插件");
                return fail;
            }
            Path target = pluginsDir.resolve(fileName);
            Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
            pluginManager.loadPlugin(target);
            return pluginManager.getPlugin(fileName.substring(0, fileName.length() - 5));
        } catch (Exception e) {
            log.error("安装插件失败: {}", sourceFile, e);
            String name = sourceFile.getFileName().toString();
            Plugin fail = new Plugin(name, name, sourceFile.toString());
            fail.setStatus(Plugin.LoadStatus.FAILED);
            fail.setErrorMessage(e.getMessage());
            return fail;
        }
    }

    /** 卸载插件: 从注册表移除并停止生效(源文件保留, 下次启动会重新加载)。 */
    public void unloadPlugin(String pluginId) {
        pluginManager.unloadPlugin(pluginId);
    }

    /** 获取插件目录。 */
    public Path getPluginsDir() {
        return pluginsDir;
    }

    /** 获取扩展注册表(供 UI 扩展等使用) */
    public ExtensionRegistry getRegistry() {
        return pluginManager.getRegistry();
    }

    /** 获取插件上下文 */
    public PluginContext getPluginContext() {
        return pluginContext;
    }

    // ==================== 内部工具 ====================

    private Path resolveDataDir() {
        if (appProperties.getDataDir() != null && !appProperties.getDataDir().isBlank()) {
            return Path.of(appProperties.getDataDir());
        }
        return Path.of(System.getProperty("user.home"), ".api-client");
    }
}
