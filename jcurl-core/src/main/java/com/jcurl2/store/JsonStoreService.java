package com.jcurl2.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * JSON 文件存储服务 — 封装所有 {@code .api-client/} 目录下的 JSON 文件读写。
 * <p>
 * 特性:
 * <ul>
 *   <li>应用启动时自动创建完整目录结构(collections/ environments/ plugins/ reports/ 等)</li>
 *   <li>原子写入: 先写 {@code .tmp} 临时文件,再 {@code Files.move} 原子重命名,避免崩溃导致文件损坏</li>
 *   <li>泛型读写: 基于 Jackson ObjectMapper,支持任意 POJO</li>
 *   <li>文件列举与删除</li>
 * </ul>
 */
@Service
public class JsonStoreService {

    private static final Logger log = LoggerFactory.getLogger(JsonStoreService.class);

    /** 需要自动创建的子目录 */
    private static final String[] SUB_DIRS = {
            "collections", "environments", "reports",
            "plugins/loaded", "plugins/disabled", "plugins/logs"
    };

    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private Path baseDir;

    public JsonStoreService(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 应用启动时初始化数据目录结构。
     * <p>
     * 读取 {@code jcurl2.data-dir} 配置(默认 {@code .api-client/}),
     * 创建根目录与所有子目录。
     */
    @PostConstruct
    public void init() {
        baseDir = resolveDataDir();

        try {
            Files.createDirectories(baseDir);
            for (String subDir : SUB_DIRS) {
                Files.createDirectories(baseDir.resolve(subDir));
            }
            log.info("Jcurl 数据目录已就绪: {}", baseDir);
        } catch (IOException e) {
            log.error("初始化数据目录失败: {}", baseDir, e);
            throw new RuntimeException("无法初始化数据目录: " + baseDir, e);
        }
    }

    /**
     * 解析数据目录路径。
     * <p>
     * 优先级:
     * 1. 系统属性 jcurl.data-dir
     * 2. 环境变量 JCURL_DATA_DIR
     * 3. 配置文件中的 jcurl2.data-dir
     * 4. 默认: 当前工作目录下 .api-client
     */
    private Path resolveDataDir() {
        String sysProp = System.getProperty("jcurl.data-dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        String envVar = System.getenv("JCURL_DATA_DIR");
        if (envVar != null && !envVar.isBlank()) {
            return Path.of(envVar);
        }
        String configured = properties.getDataDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.dir"), ".api-client");
    }

    /** 获取数据根目录 */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * 读取 JSON 文件并反序列化为指定类型。
     *
     * @param relativePath 相对于 {@code .api-client/} 的路径,如 {@code "collections/订单服务.json"}
     * @param type         目标类型
     * @return 反序列化对象,文件不存在时返回 {@code null}
     */
    public <T> T read(String relativePath, Class<T> type) {
        Path file = baseDir.resolve(relativePath);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            log.error("读取 JSON 文件失败: {}", file, e);
            throw new RuntimeException("读取文件失败: " + relativePath, e);
        }
    }

    /**
     * 原子写入对象为 JSON 文件。
     * <p>
     * 先写入 {@code .tmp} 临时文件,再原子重命名为目标文件,避免写入过程中崩溃导致损坏。
     *
     * @param relativePath 相对路径
     * @param data         要序列化的对象
     */
    public <T> void write(String relativePath, T data) {
        Path file = baseDir.resolve(relativePath);
        Path tempFile = baseDir.resolve(relativePath + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(tempFile.toFile(), data);
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("写入 JSON 文件失败: {}", file, e);
            throw new RuntimeException("写入文件失败: " + relativePath, e);
        }
    }

    /**
     * 列举指定目录下的所有文件(不含目录)。
     *
     * @param dirRelativePath 相对目录路径,如 {@code "collections"}
     * @return 文件路径列表(相对于根目录),目录不存在时返回空列表
     */
    public List<Path> listFiles(String dirRelativePath) {
        Path dir = baseDir.resolve(dirRelativePath);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(result::add);
        } catch (IOException e) {
            log.error("列举目录失败: {}", dir, e);
        }
        return result;
    }

    /**
     * 删除指定文件。
     *
     * @param relativePath 相对路径
     */
    public void delete(String relativePath) {
        Path file = baseDir.resolve(relativePath);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("删除文件失败: {}", file, e);
            throw new RuntimeException("删除文件失败: " + relativePath, e);
        }
    }

    /** 判断文件是否存在 */
    public boolean exists(String relativePath) {
        return Files.exists(baseDir.resolve(relativePath));
    }
}
