package com.jcurl.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.EnvironmentFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境文件存储 — 负责 {@link EnvironmentFile} 的 JSON 文件持久化。
 * <p>
 * 每个 EnvironmentFile 对应 environments/ 目录下的一个 .json 文件,
 * 文件名由环境名称安全化后生成 (非字母数字汉字字符替换为下划线)。
 * 文件不存在或解析失败时返回 null/空列表, 不抛异常, 记录 warn 日志。
 */
@Component
public class EnvironmentStore {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentStore.class);

    private final ObjectMapper objectMapper;
    private final Path environmentsDir;

    public EnvironmentStore(ObjectMapper objectMapper,
                            @Qualifier("dataDirPath") Path dataDir) {
        this.objectMapper = objectMapper;
        this.environmentsDir = dataDir.resolve("environments");
    }

    /**
     * 加载所有环境文件。
     * <p>
     * 扫描 environments/ 目录下所有 .json 文件并反序列化。
     * 目录不存在或文件解析失败时跳过并记录警告, 返回已成功加载的列表。
     *
     * @return 环境列表, 不会返回 null
     */
    public List<EnvironmentFile> loadAll() {
        List<EnvironmentFile> result = new ArrayList<>();
        if (!Files.isDirectory(environmentsDir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(environmentsDir, "*.json")) {
            for (Path file : stream) {
                EnvironmentFile env = readFile(file);
                if (env != null) {
                    result.add(env);
                }
            }
        } catch (IOException e) {
            log.warn("扫描环境目录失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 按 ID 加载环境。
     * <p>
     * 扫描所有环境文件, 返回 id 匹配的环境, 找不到返回 null。
     *
     * @param id 环境 ID
     * @return 环境对象, 或 null
     */
    public EnvironmentFile load(String id) {
        if (id == null) {
            return null;
        }
        for (EnvironmentFile env : loadAll()) {
            if (id.equals(env.getId())) {
                return env;
            }
        }
        return null;
    }

    /**
     * 保存环境到 JSON 文件。
     * <p>
     * 文件名由环境名称安全化生成 (如 "dev.json"), 若名称为空则使用 id。
     * 若环境曾以不同名称保存, 会先按 id 查找并删除旧文件, 避免残留。
     *
     * @param env 环境对象
     */
    public void save(EnvironmentFile env) {
        if (env == null) {
            return;
        }
        try {
            Files.createDirectories(environmentsDir);
            // 若名称变更, 先删除旧文件 (按 id 匹配)
            deleteByIdQuietly(env.getId());
            String baseName = (env.getName() != null && !env.getName().trim().isEmpty())
                    ? sanitizeFileName(env.getName())
                    : env.getId();
            Path file = environmentsDir.resolve(baseName + ".json");
            objectMapper.writeValue(file.toFile(), env);
        } catch (IOException e) {
            log.warn("保存环境失败: {}", e.getMessage());
        }
    }

    /**
     * 按 ID 删除环境文件。
     * <p>
     * 扫描所有环境文件, 删除 id 匹配的文件。找不到时不报错。
     *
     * @param id 环境 ID
     */
    public void delete(String id) {
        deleteByIdQuietly(id);
    }

    /**
     * 静默删除指定 id 对应的环境文件 (不抛异常)。
     */
    private void deleteByIdQuietly(String id) {
        if (id == null || !Files.isDirectory(environmentsDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(environmentsDir, "*.json")) {
            for (Path file : stream) {
                EnvironmentFile env = readFile(file);
                if (env != null && id.equals(env.getId())) {
                    Files.deleteIfExists(file);
                    return;
                }
            }
        } catch (IOException e) {
            log.warn("删除环境文件失败: {}", e.getMessage());
        }
    }

    /**
     * 读取并反序列化单个环境文件。
     * 文件不存在或解析失败返回 null 并记录警告。
     */
    private EnvironmentFile readFile(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), EnvironmentFile.class);
        } catch (IOException e) {
            log.warn("解析环境文件失败 {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 文件名安全化: 将非字母数字汉字字符替换为下划线。
     */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
    }
}
