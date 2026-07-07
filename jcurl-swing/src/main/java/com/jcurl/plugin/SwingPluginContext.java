package com.jcurl.plugin;

import com.jcurl.plugin.PluginContext;

import java.nio.file.Path;

/**
 * Swing 版插件上下文实现 — 为插件提供数据目录与日志能力。
 * <p>
 * 实现 {@link PluginContext} 共享接口, 使 Swing 版插件与 core/JavaFX 版插件
 * 使用统一的上下文契约。dataDir 通常为插件目录的父目录 (如 .api-client/)。
 */
public class SwingPluginContext implements PluginContext {

    private final Path dataDir;

    public SwingPluginContext(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public Path getDataDir() {
        return dataDir;
    }

    @Override
    public Path getPluginDataDir(String pluginId) {
        return dataDir.resolve("plugins").resolve(pluginId);
    }

    @Override
    public void log(String level, String message) {
        switch (level) {
            case "error":
                System.err.println("[Plugin] " + message);
                break;
            case "warn":
                System.out.println("[Plugin][WARN] " + message);
                break;
            default:
                System.out.println("[Plugin] " + message);
        }
    }

    @Override
    public void log(String level, String message, Throwable throwable) {
        log(level, message);
        throwable.printStackTrace(System.err);
    }
}
