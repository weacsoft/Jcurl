package com.jcurl2.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.model.Collection;
import com.jcurl2.model.Environment;
import com.jcurl2.model.dto.RequestConfig;
import com.jcurl2.model.dto.ResponseData;
import com.jcurl2.service.CollectionService;
import com.jcurl2.service.EnvironmentService;
import com.jcurl2.service.HistoryService;
import com.jcurl2.service.VariableResolver;
import com.jcurl2.service.VariableScope;

import java.nio.file.Path;
import java.util.Map;

/**
 * 插件上下文 — 为插件提供宿主程序的服务访问能力。
 * <p>
 * 插件通过 {@link #get} 系列方法访问宿主功能,避免直接依赖 Spring 容器。
 * 插件只能访问此接口暴露的能力,确保插件沙箱安全。
 */
public interface PluginContext {

    /** 获取数据存储根目录 */
    Path getDataDir();

    /** 获取插件专属数据目录(每个插件独立,不会互相干扰) */
    Path getPluginDataDir(String pluginId);

    /** 获取全局 ObjectMapper */
    ObjectMapper getObjectMapper();

    /** 获取 CollectionService */
    CollectionService getCollectionService();

    /** 获取 EnvironmentService */
    EnvironmentService getEnvironmentService();

    /** 获取 HistoryService */
    HistoryService getHistoryService();

    /** 获取 VariableResolver */
    VariableResolver getVariableResolver();

    /** 构建当前变量作用域 */
    VariableScope buildVariableScope(Collection collection);

    /** 记录日志(插件不应直接使用 SLF4J,通过此方法记录) */
    void log(String level, String message);

    /** 记录日志(带异常) */
    void log(String level, String message, Throwable throwable);
}
