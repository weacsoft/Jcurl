package com.jpostman2.service;

import com.jpostman2.model.Collection;
import com.jpostman2.model.Environment;
import com.jpostman2.model.GlobalVariables;

import java.util.Map;

/**
 * 变量作用域 — 封装 4 级变量来源,优先级从高到低:
 * <ol>
 *   <li>Local(本地变量): 请求级临时变量,前置脚本生成,请求结束销毁</li>
 *   <li>Environment(环境变量): 绑定当前激活环境,切换环境时自动切换值</li>
 *   <li>Collection(集合变量): 绑定在 Collection 上,该集合下所有请求共享</li>
 *   <li>Global(全局变量): 跨所有集合、所有环境生效</li>
 * </ol>
 */
public class VariableScope {

    private final Map<String, String> local;
    private final Environment activeEnvironment;
    private final Collection collection;
    private final GlobalVariables globals;

    public VariableScope(Map<String, String> local, Environment activeEnvironment,
                         Collection collection, GlobalVariables globals) {
        this.local = local != null ? local : Map.of();
        this.activeEnvironment = activeEnvironment;
        this.collection = collection;
        this.globals = globals;
    }

    /** 创建空作用域(无任何变量) */
    public static VariableScope empty() {
        return new VariableScope(null, null, null, null);
    }

    public Map<String, String> getLocal() { return local; }
    public Environment getActiveEnvironment() { return activeEnvironment; }
    public Collection getCollection() { return collection; }
    public GlobalVariables getGlobals() { return globals; }
}
