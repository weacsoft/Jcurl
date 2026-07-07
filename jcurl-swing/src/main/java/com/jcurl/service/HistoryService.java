package com.jcurl.service;

import com.jcurl.model.HistoryRecord;
import com.jcurl.model.KeyValue;
import com.jcurl.model.dto.RequestConfig;
import com.jcurl.model.dto.ResponseData;
import com.jcurl.service.store.HistoryStore;
import com.jcurl.service.store.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史记录服务。
 * <p>
 * 基于 JSON 文件存储, 记录、查询、删除请求执行历史。
 * 响应体超过 1MB 时截断存储, 历史记录默认保留最近 500 条 (可配置)。
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    /** 响应体最大存储字符数 (1MB) */
    private static final int MAX_RESPONSE_BODY_LENGTH = 1048576;

    private final HistoryStore historyStore;
    private final SettingsStore settingsStore;

    public HistoryService(HistoryStore historyStore, SettingsStore settingsStore) {
        this.historyStore = historyStore;
        this.settingsStore = settingsStore;
    }

    /**
     * 创建并保存历史记录。
     *
     * @param requestName 请求名称, 可为 null
     * @param config      请求配置, 不能为 null
     * @param response    响应数据, 不能为 null
     * @return 已保存的历史记录
     */
    public HistoryRecord record(String requestName, RequestConfig config, ResponseData response) {
        HistoryRecord history = new HistoryRecord();
        history.setRequestName(requestName);
        history.setMethod(config.getMethod());
        history.setUrl(config.getUrl());
        history.setRequestHeaders(extractEnabledKeyValues(config.getHeaders()));
        history.setRequestBody(config.getBodyContent());
        history.setBodyType(config.getBodyType());
        history.setStatusCode(response.getStatusCode());
        history.setStatusText(response.getStatusText());
        history.setResponseHeaders(mapToKeyValues(response.getResponseHeaders()));
        history.setResponseBody(truncateBody(response.getResponseBody()));
        history.setResponseTime(response.getResponseTime());
        history.setResponseSize(response.getResponseSize());

        // 添加并执行容量限制
        List<HistoryRecord> all = historyStore.loadAll();
        all.add(0, history); // 新记录插入头部

        int limit = settingsStore.load().getHistoryLimit();
        if (all.size() > limit) {
            all = new ArrayList<>(all.subList(0, limit));
        }

        historyStore.saveAll(all);
        return history;
    }

    /**
     * 创建并保存历史记录 (不带请求名称)。
     */
    public HistoryRecord record(RequestConfig config, ResponseData response) {
        return record(null, config, response);
    }

    /**
     * 获取所有历史记录 (按时间倒序,新记录在前)。
     */
    public List<HistoryRecord> getAllHistory() {
        List<HistoryRecord> all = new ArrayList<>(historyStore.loadAll());
        all.sort(Comparator.comparing(HistoryRecord::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    /**
     * 搜索历史记录。
     * <p>
     * keyword/method/statusCode 为 null 时忽略该条件。
     *
     * @param keyword URL 模糊匹配关键字, 可为 null
     * @param method  HTTP 方法, 可为 null
     * @return 匹配的历史记录列表
     */
    public List<HistoryRecord> search(String keyword, String method) {
        List<HistoryRecord> all = historyStore.loadAll();
        List<HistoryRecord> result = new ArrayList<>();
        for (HistoryRecord h : all) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                String url = h.getUrl() != null ? h.getUrl() : "";
                if (!url.toLowerCase().contains(keyword.toLowerCase())) {
                    continue;
                }
            }
            if (method != null && !method.trim().isEmpty()) {
                if (!method.equalsIgnoreCase(h.getMethod())) {
                    continue;
                }
            }
            result.add(h);
        }
        // 按时间倒序排序(新记录在前),与 getAllHistory 保持一致
        result.sort(Comparator.comparing(HistoryRecord::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * 删除单条历史记录。
     */
    public void deleteHistory(String id) {
        historyStore.delete(id);
    }

    /**
     * 清空所有历史记录。
     */
    public void clearAllHistory() {
        historyStore.clearAll();
    }

    /**
     * 提取 enabled 的 KeyValue 列表副本。
     */
    private List<KeyValue> extractEnabledKeyValues(List<KeyValue> kvs) {
        List<KeyValue> result = new ArrayList<>();
        if (kvs != null) {
            for (KeyValue kv : kvs) {
                if (kv.isEnabled() && kv.getKey() != null) {
                    result.add(new KeyValue(kv.getKey(), kv.getValue(), kv.getDescription(), true));
                }
            }
        }
        return result;
    }

    /**
     * 将 Map 转为 KeyValue 列表。
     */
    private List<KeyValue> mapToKeyValues(Map<String, String> map) {
        List<KeyValue> result = new ArrayList<>();
        if (map != null) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                result.add(new KeyValue(entry.getKey(), entry.getValue(), "", true));
            }
        }
        return result;
    }

    /**
     * 截断响应体到 1MB。
     */
    private String truncateBody(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() > MAX_RESPONSE_BODY_LENGTH) {
            return body.substring(0, MAX_RESPONSE_BODY_LENGTH);
        }
        return body;
    }
}
