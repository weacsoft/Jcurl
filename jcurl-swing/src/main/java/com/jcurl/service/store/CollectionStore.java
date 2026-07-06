package com.jcurl.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.CollectionFile;
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
 * 集合文件存储 — 负责 {@link CollectionFile} 的 JSON 文件持久化。
 * <p>
 * 每个 CollectionFile 对应 collections/ 目录下的一个 .json 文件,
 * 文件名由集合名称安全化后生成 (非字母数字汉字字符替换为下划线)。
 * 文件不存在或解析失败时返回 null/空列表, 不抛异常, 记录 warn 日志。
 */
@Component
public class CollectionStore {

    private static final Logger log = LoggerFactory.getLogger(CollectionStore.class);

    private final ObjectMapper objectMapper;
    private final Path collectionsDir;

    public CollectionStore(ObjectMapper objectMapper,
                           @Qualifier("dataDirPath") Path dataDir) {
        this.objectMapper = objectMapper;
        this.collectionsDir = dataDir.resolve("collections");
    }

    /**
     * 加载所有集合文件。
     * <p>
     * 扫描 collections/ 目录下所有 .json 文件并反序列化。
     * 目录不存在或文件解析失败时跳过并记录警告, 返回已成功加载的列表。
     *
     * @return 集合列表, 不会返回 null
     */
    public List<CollectionFile> loadAll() {
        List<CollectionFile> result = new ArrayList<>();
        if (!Files.isDirectory(collectionsDir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(collectionsDir, "*.json")) {
            for (Path file : stream) {
                CollectionFile collection = readFile(file);
                if (collection != null) {
                    result.add(collection);
                }
            }
        } catch (IOException e) {
            log.warn("扫描集合目录失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 按 ID 加载集合。
     * <p>
     * 扫描所有集合文件, 返回 id 匹配的集合, 找不到返回 null。
     *
     * @param id 集合 ID
     * @return 集合对象, 或 null
     */
    public CollectionFile load(String id) {
        if (id == null) {
            return null;
        }
        for (CollectionFile collection : loadAll()) {
            if (id.equals(collection.getId())) {
                return collection;
            }
        }
        return null;
    }

    /**
     * 按名称加载集合。
     * <p>
     * 扫描所有集合文件, 返回 name 匹配的集合, 找不到返回 null。
     *
     * @param name 集合名称
     * @return 集合对象, 或 null
     */
    public CollectionFile loadByName(String name) {
        if (name == null) {
            return null;
        }
        for (CollectionFile collection : loadAll()) {
            if (name.equals(collection.getName())) {
                return collection;
            }
        }
        return null;
    }

    /**
     * 保存集合到 JSON 文件。
     * <p>
     * 文件名由集合名称安全化生成 (如 "订单服务.json"), 若名称为空则使用 id。
     * 若集合曾以不同名称保存, 会先按 id 查找并删除旧文件, 避免残留。
     *
     * @param collection 集合对象
     */
    public void save(CollectionFile collection) {
        if (collection == null) {
            return;
        }
        try {
            Files.createDirectories(collectionsDir);
            // 若名称变更, 先删除旧文件 (按 id 匹配)
            deleteByIdQuietly(collection.getId());
            String baseName = (collection.getName() != null && !collection.getName().trim().isEmpty())
                    ? sanitizeFileName(collection.getName())
                    : collection.getId();
            Path file = collectionsDir.resolve(baseName + ".json");
            objectMapper.writeValue(file.toFile(), collection);
        } catch (IOException e) {
            log.warn("保存集合失败: {}", e.getMessage());
        }
    }

    /**
     * 按 ID 删除集合文件。
     * <p>
     * 扫描所有集合文件, 删除 id 匹配的文件。找不到时不报错。
     *
     * @param id 集合 ID
     */
    public void delete(String id) {
        deleteByIdQuietly(id);
    }

    /**
     * 静默删除指定 id 对应的集合文件 (不抛异常)。
     */
    private void deleteByIdQuietly(String id) {
        if (id == null || !Files.isDirectory(collectionsDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(collectionsDir, "*.json")) {
            for (Path file : stream) {
                CollectionFile collection = readFile(file);
                if (collection != null && id.equals(collection.getId())) {
                    Files.deleteIfExists(file);
                    return;
                }
            }
        } catch (IOException e) {
            log.warn("删除集合文件失败: {}", e.getMessage());
        }
    }

    /**
     * 读取并反序列化单个集合文件。
     * 文件不存在或解析失败返回 null 并记录警告。
     */
    private CollectionFile readFile(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), CollectionFile.class);
        } catch (IOException e) {
            log.warn("解析集合文件失败 {}: {}", file, e.getMessage());
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
