package com.jpostman.service;

import com.jpostman.model.EnvironmentFile;
import com.jpostman.model.Variable;
import com.jpostman.service.store.EnvironmentStore;
import com.jpostman.service.store.GlobalVariableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境与变量管理服务 — v2 架构。
 * <p>
 * 基于 JSON 文件存储, 管理环境(EnvironmentFile)与全局变量。
 * <p>
 * v2 变量作用域 (4级优先级, 从高到低):
 * 1. 本地变量 (Local) — 仅在请求执行期间临时生成, 不持久化
 * 2. 环境变量 (Environment) — 绑定当前激活环境
 * 3. 集合变量 (Collection) — 绑定请求所属集合
 * 4. 全局变量 (Global) — 跨所有集合和环境生效
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentStore environmentStore;
    private final GlobalVariableStore globalVariableStore;

    /** 当前激活环境 ID, 内存态不持久化 */
    private String activeEnvironmentId;

    public EnvironmentService(EnvironmentStore environmentStore,
                               GlobalVariableStore globalVariableStore) {
        this.environmentStore = environmentStore;
        this.globalVariableStore = globalVariableStore;
    }

    // ==================== 环境 ====================

    /**
     * 获取所有环境。
     */
    public List<EnvironmentFile> getAllEnvironments() {
        return environmentStore.loadAll();
    }

    /**
     * 按 ID 获取环境。
     */
    public EnvironmentFile getEnvironment(String id) {
        return environmentStore.load(id);
    }

    /**
     * 创建环境。
     */
    public EnvironmentFile createEnvironment(String name) {
        EnvironmentFile env = new EnvironmentFile(name);
        environmentStore.save(env);
        return env;
    }

    /**
     * 更新环境。
     */
    public void updateEnvironment(EnvironmentFile env) {
        if (env != null) {
            environmentStore.save(env);
        }
    }

    /**
     * 删除环境。
     */
    public void deleteEnvironment(String id) {
        environmentStore.delete(id);
        if (id.equals(activeEnvironmentId)) {
            activeEnvironmentId = null;
        }
    }

    /**
     * 设置激活环境。null 表示取消激活。
     */
    public void setActiveEnvironment(String id) {
        this.activeEnvironmentId = id;
    }

    /**
     * 返回当前激活环境 ID, 未激活返回 null。
     */
    public String getActiveEnvironmentId() {
        return activeEnvironmentId;
    }

    /**
     * 返回当前激活环境, 未激活返回 null。
     */
    public EnvironmentFile getActiveEnvironment() {
        if (activeEnvironmentId == null) {
            return null;
        }
        return environmentStore.load(activeEnvironmentId);
    }

    /**
     * 返回当前激活环境的变量 Map, 未激活返回空 Map。
     */
    public Map<String, String> getActiveVariables() {
        if (activeEnvironmentId == null) {
            return new HashMap<>();
        }
        EnvironmentFile env = environmentStore.load(activeEnvironmentId);
        if (env == null) {
            return new HashMap<>();
        }
        return variablesToMap(env.getVariables());
    }

    // ==================== 全局变量 ====================

    /**
     * 获取所有全局变量。
     */
    public List<Variable> getGlobalVariables() {
        return globalVariableStore.loadAll();
    }

    /**
     * 保存全局变量列表 (全量覆盖)。
     */
    public void saveGlobalVariables(List<Variable> variables) {
        globalVariableStore.saveAll(variables);
    }

    /**
     * 创建全局变量。
     */
    public Variable createGlobalVariable(String key, String value, String description) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("全局变量 key 不能为空");
        }
        List<Variable> variables = new ArrayList<>(globalVariableStore.loadAll());
        Variable var = new Variable();
        var.setKey(key);
        var.setValue(value);
        var.setDescription(description);
        variables.add(var);
        globalVariableStore.saveAll(variables);
        return var;
    }

    /**
     * 删除全局变量 (按 key)。
     */
    public void deleteGlobalVariable(String key) {
        List<Variable> variables = globalVariableStore.loadAll();
        variables.removeIf(v -> v.getKey().equals(key));
        globalVariableStore.saveAll(variables);
    }

    // ==================== 有效变量合并 ====================

    /**
     * 合并全局变量与激活环境变量, 返回有效变量集合。
     * <p>
     * 先取全局变量, 再用激活环境变量覆盖 (环境变量优先级更高)。
     *
     * @return 有效变量 Map
     */
    public Map<String, String> getEffectiveVariables() {
        Map<String, String> effective = new HashMap<>();
        // 全局变量
        for (Variable var : globalVariableStore.loadAll()) {
            if (var.isEnabled() && var.getKey() != null) {
                effective.put(var.getKey(), var.getValue());
            }
        }
        // 环境变量覆盖
        if (activeEnvironmentId != null) {
            EnvironmentFile env = environmentStore.load(activeEnvironmentId);
            if (env != null) {
                for (Variable var : env.getVariables()) {
                    if (var.isEnabled() && var.getKey() != null) {
                        effective.put(var.getKey(), var.getValue());
                    }
                }
            }
        }
        return effective;
    }

    /**
     * 合并全局变量、环境变量和集合变量, 返回完整有效变量集合。
     * <p>
     * 优先级: 环境变量 > 集合变量 > 全局变量。
     *
     * @param collectionVariables 集合级变量列表, 可为 null
     * @return 有效变量 Map
     */
    public Map<String, String> getEffectiveVariables(List<Variable> collectionVariables) {
        Map<String, String> effective = new HashMap<>();
        // 全局变量 (最低优先级)
        for (Variable var : globalVariableStore.loadAll()) {
            if (var.isEnabled() && var.getKey() != null) {
                effective.put(var.getKey(), var.getValue());
            }
        }
        // 集合变量
        if (collectionVariables != null) {
            for (Variable var : collectionVariables) {
                if (var.isEnabled() && var.getKey() != null) {
                    effective.put(var.getKey(), var.getValue());
                }
            }
        }
        // 环境变量 (最高优先级)
        if (activeEnvironmentId != null) {
            EnvironmentFile env = environmentStore.load(activeEnvironmentId);
            if (env != null) {
                for (Variable var : env.getVariables()) {
                    if (var.isEnabled() && var.getKey() != null) {
                        effective.put(var.getKey(), var.getValue());
                    }
                }
            }
        }
        return effective;
    }

    /**
     * 将变量列表转为 Map (仅 enabled 的变量)。
     */
    private Map<String, String> variablesToMap(List<Variable> variables) {
        Map<String, String> map = new HashMap<>();
        if (variables != null) {
            for (Variable var : variables) {
                if (var.isEnabled() && var.getKey() != null) {
                    map.put(var.getKey(), var.getValue());
                }
            }
        }
        return map;
    }
}
