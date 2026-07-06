package com.jcurl2.service;

import com.jcurl2.model.Environment;
import com.jcurl2.model.GlobalVariables;
import com.jcurl2.model.Settings;
import com.jcurl2.model.component.Variable;
import com.jcurl2.store.JsonStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 环境管理服务 — 管理环境、全局变量与用户设置的持久化。
 * <p>
 * 存储路径:
 * <ul>
 *   <li>环境: {@code ~/.api-client/environments/{environmentId}.json}</li>
 *   <li>全局变量: {@code ~/.api-client/globals.json}</li>
 *   <li>设置: {@code ~/.api-client/settings.json}</li>
 * </ul>
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private static final String ENVIRONMENTS_DIR = "environments";
    private static final String GLOBALS_FILE = "globals.json";
    private static final String SETTINGS_FILE = "settings.json";

    private final JsonStoreService store;

    public EnvironmentService(JsonStoreService store) {
        this.store = store;
    }

    // ==================== 环境 CRUD ====================

    /**
     * 创建新环境。
     *
     * @param name 环境名称(如 "开发环境""测试环境")
     * @return 已创建的 Environment
     */
    public Environment createEnvironment(String name) {
        String id = UUID.randomUUID().toString();
        Environment env = new Environment(id, name);
        saveEnvironment(env);
        log.info("创建环境: id={}, name={}", id, name);
        return env;
    }

    /**
     * 列举所有环境。
     *
     * @return 环境列表
     */
    public List<Environment> listEnvironments() {
        List<Environment> result = new ArrayList<>();
        List<Path> files = store.listFiles(ENVIRONMENTS_DIR);
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".json") || fileName.endsWith(".tmp")) {
                continue;
            }
            try {
                Environment env = store.read(ENVIRONMENTS_DIR + "/" + fileName, Environment.class);
                if (env != null) {
                    result.add(env);
                }
            } catch (Exception e) {
                log.warn("加载环境文件失败: {}", fileName, e);
            }
        }
        return result;
    }

    /**
     * 加载指定环境。
     *
     * @param environmentId 环境 ID
     * @return 环境对象,不存在返回 null
     */
    public Environment loadEnvironment(String environmentId) {
        return store.read(ENVIRONMENTS_DIR + "/" + environmentId + ".json", Environment.class);
    }

    /**
     * 保存环境。
     *
     * @param environment 要保存的环境
     */
    public void saveEnvironment(Environment environment) {
        store.write(ENVIRONMENTS_DIR + "/" + environment.getId() + ".json", environment);
    }

    /**
     * 删除环境。如果删除的是当前激活环境,清除激活状态。
     *
     * @param environmentId 环境 ID
     */
    public void deleteEnvironment(String environmentId) {
        store.delete(ENVIRONMENTS_DIR + "/" + environmentId + ".json");
        Settings settings = loadSettings();
        if (environmentId.equals(settings.getActiveEnvironmentId())) {
            settings.setActiveEnvironmentId(null);
            saveSettings(settings);
        }
        log.info("删除环境: id={}", environmentId);
    }

    // ==================== 当前环境管理 ====================

    /**
     * 获取当前激活的环境。
     *
     * @return 激活的环境,未设置或不存在返回 null
     */
    public Environment getActiveEnvironment() {
        Settings settings = loadSettings();
        String activeId = settings.getActiveEnvironmentId();
        if (activeId == null || activeId.isBlank()) {
            return null;
        }
        return loadEnvironment(activeId);
    }

    /**
     * 设置当前激活环境。
     *
     * @param environmentId 环境 ID(null 表示取消激活)
     */
    public void setActiveEnvironment(String environmentId) {
        Settings settings = loadSettings();
        settings.setActiveEnvironmentId(environmentId);
        saveSettings(settings);
        log.info("切换激活环境: id={}", environmentId);
    }

    // ==================== 全局变量 ====================

    /**
     * 加载全局变量。
     *
     * @return 全局变量对象(不为 null)
     */
    public GlobalVariables loadGlobals() {
        GlobalVariables globals = store.read(GLOBALS_FILE, GlobalVariables.class);
        return globals != null ? globals : new GlobalVariables();
    }

    /**
     * 保存全局变量。
     *
     * @param globals 全局变量对象
     */
    public void saveGlobals(GlobalVariables globals) {
        store.write(GLOBALS_FILE, globals);
    }

    // ==================== 设置 ====================

    /**
     * 加载用户设置。
     *
     * @return 设置对象(不为 null,首次使用返回默认值)
     */
    public Settings loadSettings() {
        Settings settings = store.read(SETTINGS_FILE, Settings.class);
        return settings != null ? settings : new Settings();
    }

    /**
     * 保存用户设置。
     *
     * @param settings 设置对象
     */
    public void saveSettings(Settings settings) {
        store.write(SETTINGS_FILE, settings);
    }

    // ==================== 变量作用域构建 ====================

    /**
     * 构建当前变量作用域(用于 VariableResolver)。
     * <p>
     * 优先级: Local > Environment(active) > Collection > Global
     *
     * @param collection 当前集合(提供集合级变量,null 表示无)
     * @return 构建好的 VariableScope
     */
    public VariableScope buildScope(com.jcurl2.model.Collection collection) {
        // VariableScope 为不可变对象,通过构造函数一次性传入各级变量来源
        return new VariableScope(null, getActiveEnvironment(), collection, loadGlobals());
    }

    /**
     * 构建空作用域(无环境变量,仅解析动态函数)。
     *
     * @return 空 VariableScope
     */
    public VariableScope buildEmptyScope() {
        return VariableScope.empty();
    }
}
