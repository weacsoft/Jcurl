package com.jpostman.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman.model.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局变量存储 — 负责 {@link Variable} 列表的 JSON 文件持久化。
 * <p>
 * 所有全局变量保存在单个 globals.json 文件中, 使用 {@link GlobalVariableData} 包装对象承载列表。
 * 文件不存在或解析失败时返回空列表, 不抛异常, 记录 warn 日志。
 */
@Component
public class GlobalVariableStore {

    private static final Logger log = LoggerFactory.getLogger(GlobalVariableStore.class);

    private final ObjectMapper objectMapper;
    private final Path globalsFile;

    public GlobalVariableStore(ObjectMapper objectMapper,
                               @Qualifier("dataDirPath") Path dataDir) {
        this.objectMapper = objectMapper;
        this.globalsFile = dataDir.resolve("globals.json");
    }

    /**
     * 加载所有全局变量。
     * <p>
     * 文件不存在或解析失败时返回空列表。
     *
     * @return 全局变量列表, 不会返回 null
     */
    public List<Variable> loadAll() {
        GlobalVariableData data = readData();
        if (data == null || data.getVariables() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(data.getVariables());
    }

    /**
     * 保存全部全局变量。
     * <p>
     * 将整个列表序列化写入 globals.json。
     *
     * @param variables 全局变量列表
     */
    public void saveAll(List<Variable> variables) {
        GlobalVariableData data = new GlobalVariableData();
        data.setVariables(variables != null ? variables : new ArrayList<Variable>());
        writeData(data);
    }

    /**
     * 读取并反序列化 globals.json。
     * 文件不存在或解析失败返回 null 并记录警告。
     */
    private GlobalVariableData readData() {
        if (!Files.isRegularFile(globalsFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(globalsFile.toFile(), GlobalVariableData.class);
        } catch (IOException e) {
            log.warn("解析全局变量文件失败 {}: {}", globalsFile, e.getMessage());
            return null;
        }
    }

    /**
     * 序列化并写入 globals.json。
     */
    private void writeData(GlobalVariableData data) {
        try {
            Path parent = globalsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(globalsFile.toFile(), data);
        } catch (IOException e) {
            log.warn("保存全局变量失败: {}", e.getMessage());
        }
    }

    /**
     * 全局变量包装对象 — 承载 variables 列表, 对应 globals.json 的根结构。
     */
    public static class GlobalVariableData {

        private List<Variable> variables = new ArrayList<>();

        public List<Variable> getVariables() {
            return variables;
        }

        public void setVariables(List<Variable> variables) {
            this.variables = variables != null ? variables : new ArrayList<>();
        }
    }
}
