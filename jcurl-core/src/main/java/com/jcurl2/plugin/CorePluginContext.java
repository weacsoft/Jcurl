package com.jcurl2.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.model.Collection;
import com.jcurl2.service.CollectionService;
import com.jcurl2.service.EnvironmentService;
import com.jcurl2.service.HistoryService;
import com.jcurl2.service.VariableResolver;
import com.jcurl2.service.VariableScope;

/**
 * Core 版插件上下文 — 继承共享 PluginContext，添加 core 特有的服务访问。
 * 插件可通过 cast 到 CorePluginContext 访问宿主服务。
 */
public interface CorePluginContext extends PluginContext {
    ObjectMapper getObjectMapper();
    CollectionService getCollectionService();
    EnvironmentService getEnvironmentService();
    HistoryService getHistoryService();
    VariableResolver getVariableResolver();
    VariableScope buildVariableScope(Collection collection);
}
