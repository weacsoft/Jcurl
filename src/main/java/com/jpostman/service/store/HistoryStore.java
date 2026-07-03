package com.jpostman.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman.model.HistoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 历史记录存储 — 负责 {@link HistoryRecord} 的 JSON 文件持久化。
 * <p>
 * 所有历史记录保存在单个 history.json 文件中, 使用 {@link HistoryData} 包装对象承载列表。
 * 文件不存在或解析失败时返回空列表, 不抛异常, 记录 warn 日志。
 */
@Component
public class HistoryStore {

    private static final Logger log = LoggerFactory.getLogger(HistoryStore.class);

    private final ObjectMapper objectMapper;
    private final Path historyFile;

    public HistoryStore(ObjectMapper objectMapper,
                        @Qualifier("dataDirPath") Path dataDir) {
        this.objectMapper = objectMapper;
        this.historyFile = dataDir.resolve("history.json");
    }

    /**
     * 加载所有历史记录 (按时间倒序)。
     * <p>
     * 文件不存在或解析失败时返回空列表。
     *
     * @return 历史记录列表 (时间倒序), 不会返回 null
     */
    public List<HistoryRecord> loadAll() {
        HistoryData data = readData();
        if (data == null || data.getRecords() == null) {
            return new ArrayList<>();
        }
        List<HistoryRecord> records = new ArrayList<>(data.getRecords());
        sortByTimestampDesc(records);
        return records;
    }

    /**
     * 保存全部历史记录。
     * <p>
     * 将整个列表序列化写入 history.json。
     *
     * @param records 历史记录列表
     */
    public void saveAll(List<HistoryRecord> records) {
        HistoryData data = new HistoryData();
        data.setRecords(records != null ? records : new ArrayList<HistoryRecord>());
        writeData(data);
    }

    /**
     * 添加一条历史记录。
     * <p>
     * 读取现有记录, 追加新记录后整体保存。
     *
     * @param record 历史记录
     */
    public void add(HistoryRecord record) {
        if (record == null) {
            return;
        }
        List<HistoryRecord> records = loadAll();
        records.add(0, record);
        saveAll(records);
    }

    /**
     * 删除单条历史记录。
     * <p>
     * 按 id 匹配删除, 找不到时不报错。
     *
     * @param id 历史记录 ID
     */
    public void delete(String id) {
        if (id == null) {
            return;
        }
        List<HistoryRecord> records = loadAll();
        boolean changed = false;
        Iterator<HistoryRecord> it = records.iterator();
        while (it.hasNext()) {
            HistoryRecord record = it.next();
            if (id.equals(record.getId())) {
                it.remove();
                changed = true;
                break;
            }
        }
        if (changed) {
            saveAll(records);
        }
    }

    /**
     * 清空所有历史记录。
     * <p>
     * 写入空列表, 文件保留。
     */
    public void clearAll() {
        saveAll(new ArrayList<HistoryRecord>());
    }

    /**
     * 读取并反序列化 history.json。
     * 文件不存在或解析失败返回 null 并记录警告。
     */
    private HistoryData readData() {
        if (!Files.isRegularFile(historyFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(historyFile.toFile(), HistoryData.class);
        } catch (IOException e) {
            log.warn("解析历史记录文件失败 {}: {}", historyFile, e.getMessage());
            return null;
        }
    }

    /**
     * 序列化并写入 history.json。
     */
    private void writeData(HistoryData data) {
        try {
            Path parent = historyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(historyFile.toFile(), data);
        } catch (IOException e) {
            log.warn("保存历史记录失败: {}", e.getMessage());
        }
    }

    /**
     * 按时间戳倒序排序 (null 时间戳排在最后)。
     */
    private void sortByTimestampDesc(List<HistoryRecord> records) {
        records.sort((a, b) -> {
            LocalDateTime ta = a.getTimestamp();
            LocalDateTime tb = b.getTimestamp();
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.compareTo(ta);
        });
    }

    /**
     * 历史记录包装对象 — 承载 records 列表, 对应 history.json 的根结构。
     */
    public static class HistoryData {

        private List<HistoryRecord> records = new ArrayList<>();

        public List<HistoryRecord> getRecords() {
            return records;
        }

        public void setRecords(List<HistoryRecord> records) {
            this.records = records != null ? records : new ArrayList<>();
        }
    }
}
