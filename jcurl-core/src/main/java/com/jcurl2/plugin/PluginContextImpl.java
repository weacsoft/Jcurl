package com.jcurl2.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.model.Collection;
import com.jcurl2.service.CollectionService;
import com.jcurl2.service.EnvironmentService;
import com.jcurl2.service.HistoryService;
import com.jcurl2.service.VariableResolver;
import com.jcurl2.service.VariableScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PluginContext 实现 — 为插件提供宿主服务的访问入口。
 * <p>
 * 由 {@link PluginService} 创建,注入所有宿主服务依赖。
 * 插件通过此对象访问 CollectionService、EnvironmentService 等,无需直接依赖 Spring。
 * 实现 {@link CorePluginContext} 以暴露 core 特有的服务访问方法。
 */
public class PluginContextImpl implements CorePluginContext {

    private static final Logger log = LoggerFactory.getLogger(PluginContextImpl.class);

    private final Path dataDir;
    private final ObjectMapper objectMapper;
    private final CollectionService collectionService;
    private final EnvironmentService environmentService;
    private final HistoryService historyService;
    private final VariableResolver variableResolver;

    public PluginContextImpl(Path dataDir, ObjectMapper objectMapper,
                             CollectionService collectionService,
                             EnvironmentService environmentService,
                             HistoryService historyService,
                             VariableResolver variableResolver) {
        this.dataDir = dataDir;
        this.objectMapper = objectMapper;
        this.collectionService = collectionService;
        this.environmentService = environmentService;
        this.historyService = historyService;
        this.variableResolver = variableResolver;
    }

    @Override
    public Path getDataDir() {
        return dataDir;
    }

    @Override
    public Path getPluginDataDir(String pluginId) {
        Path dir = dataDir.resolve("plugin-data").resolve(pluginId);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("创建插件数据目录失败: {}", dir, e);
        }
        return dir;
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public CollectionService getCollectionService() {
        return collectionService;
    }

    @Override
    public EnvironmentService getEnvironmentService() {
        return environmentService;
    }

    @Override
    public HistoryService getHistoryService() {
        return historyService;
    }

    @Override
    public VariableResolver getVariableResolver() {
        return variableResolver;
    }

    @Override
    public VariableScope buildVariableScope(Collection collection) {
        return environmentService.buildScope(collection);
    }

    @Override
    public void log(String level, String message) {
        switch (level.toUpperCase()) {
            case "ERROR" -> log.error("[Plugin] {}", message);
            case "WARN" -> log.warn("[Plugin] {}", message);
            case "DEBUG" -> log.debug("[Plugin] {}", message);
            default -> log.info("[Plugin] {}", message);
        }
    }

    @Override
    public void log(String level, String message, Throwable throwable) {
        switch (level.toUpperCase()) {
            case "ERROR" -> log.error("[Plugin] {}", message, throwable);
            case "WARN" -> log.warn("[Plugin] {}", message, throwable);
            case "DEBUG" -> log.debug("[Plugin] {}", message, throwable);
            default -> log.info("[Plugin] {}", message, throwable);
        }
    }
}
