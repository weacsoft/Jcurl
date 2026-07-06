package com.jcurl.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置存储 — 负责 {@link Settings} 的 JSON 文件持久化。
 * <p>
 * 用户设置保存在单个 settings.json 文件中。
 * 文件不存在或解析失败时返回默认 Settings 实例, 不抛异常, 记录 warn 日志。
 */
@Component
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private final ObjectMapper objectMapper;
    private final Path settingsFile;

    public SettingsStore(ObjectMapper objectMapper,
                         @Qualifier("dataDirPath") Path dataDir) {
        this.objectMapper = objectMapper;
        this.settingsFile = dataDir.resolve("settings.json");
    }

    /**
     * 加载用户设置。
     * <p>
     * 文件不存在或解析失败时返回默认 Settings 实例 (字段均有缺省值)。
     *
     * @return 设置对象, 不会返回 null
     */
    public Settings load() {
        if (!Files.isRegularFile(settingsFile)) {
            return new Settings();
        }
        try {
            Settings settings = objectMapper.readValue(settingsFile.toFile(), Settings.class);
            return settings != null ? settings : new Settings();
        } catch (IOException e) {
            log.warn("解析设置文件失败 {}: {}", settingsFile, e.getMessage());
            return new Settings();
        }
    }

    /**
     * 保存用户设置到 settings.json。
     *
     * @param settings 设置对象
     */
    public void save(Settings settings) {
        if (settings == null) {
            return;
        }
        try {
            Path parent = settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(settingsFile.toFile(), settings);
        } catch (IOException e) {
            log.warn("保存设置失败: {}", e.getMessage());
        }
    }
}
