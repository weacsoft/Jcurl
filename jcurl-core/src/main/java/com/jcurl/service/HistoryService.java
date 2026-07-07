package com.jcurl.service;

import com.jcurl.config.AppProperties;
import com.jcurl.model.HistoryRecord;
import com.jcurl.model.HistoryStore;
import com.jcurl.plugin.model.component.AuthConfig;
import com.jcurl.plugin.model.component.Header;
import com.jcurl.plugin.model.component.QueryParam;
import com.jcurl.plugin.model.component.RequestBody;
import com.jcurl.plugin.model.dto.RequestConfig;
import com.jcurl.plugin.model.dto.ResponseData;
import com.jcurl.plugin.model.dto.TimingMetrics;
import com.jcurl.store.JsonStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 历史记录服务 — 自动记录每次请求/响应,支持搜索、过滤、裁剪与保存到集合。
 * <p>
 * 核心能力:
 * <ul>
 *   <li>自动记录: 每次请求执行后调用 {@link #record} 保存完整请求与响应</li>
 *   <li>搜索: 按关键词匹配 URL / Method / 名称</li>
 *   <li>过滤: 按 HTTP 方法、状态码、时间范围过滤</li>
 *   <li>自动裁剪: 超过 historyLimit 时自动删除最旧记录</li>
 *   <li>响应体截断: 超过 1MB 时截断,避免 history.json 膨胀</li>
 *   <li>清空与删除: 支持单条删除与全部清空</li>
 * </ul>
 * <p>
 * 存储路径: {@code .api-client/history.json}
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private static final String HISTORY_FILE = "history.json";

    /** 响应体最大存储字节数(1MB),超出截断 */
    private static final int MAX_BODY_LENGTH = 1024 * 1024;

    private final JsonStoreService store;
    private final AppProperties properties;

    public HistoryService(JsonStoreService store, AppProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    // ==================== 记录与列举 ====================

    /**
     * 记录一次请求/响应到历史。
     * <p>
     * 自动裁剪: 记录后若超过上限,删除最旧的记录。
     *
     * @param config   请求配置(已合并继承、已解析变量)
     * @param response 响应数据
     * @return 已创建的 HistoryRecord
     */
    public HistoryRecord record(RequestConfig config, ResponseData response) {
        HistoryStore historyStore = loadStore();

        HistoryRecord record = buildRecord(config, response);
        historyStore.getRecords().add(record);

        // 裁剪
        trimHistory(historyStore);

        // 设置 limit 到 store
        historyStore.setLimit(properties.getHistoryLimit());

        saveStore(historyStore);
        log.debug("记录历史: id={}, method={}, url={}, status={}",
                record.getId(), record.getMethod(), record.getUrl(), record.getStatusCode());
        return record;
    }

    /**
     * 列举所有历史记录(按时间倒序,最新的在前)。
     *
     * @return 历史记录列表
     */
    public List<HistoryRecord> listHistory() {
        HistoryStore historyStore = loadStore();
        return historyStore.getRecords().stream()
                .sorted(Comparator.comparing(HistoryRecord::getTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定 ID 的历史记录。
     *
     * @param id 记录 ID
     * @return 历史记录,未找到返回 null
     */
    public HistoryRecord getHistory(String id) {
        return loadStore().getRecords().stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElse(null);
    }

    // ==================== 搜索与过滤 ====================

    /**
     * 按关键词搜索历史记录(匹配 URL / Method / 名称,不区分大小写)。
     *
     * @param keyword 搜索关键词,null 或空串返回全部
     * @return 匹配的历史记录列表(按时间倒序)
     */
    public List<HistoryRecord> searchHistory(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listHistory();
        }
        String lower = keyword.toLowerCase();
        return listHistory().stream()
                .filter(r -> matchesKeyword(r, lower))
                .collect(Collectors.toList());
    }

    /**
     * 多维度过滤历史记录。
     *
     * @param method     HTTP 方法过滤(null 表示不过滤)
     * @param statusCode 状态码过滤(null 表示不过滤)
     * @param startTime  开始时间(null 表示不过滤)
     * @param endTime    结束时间(null 表示不过滤)
     * @return 过滤后的历史记录列表(按时间倒序)
     */
    public List<HistoryRecord> filterHistory(String method, Integer statusCode,
                                              LocalDateTime startTime, LocalDateTime endTime) {
        return listHistory().stream()
                .filter(r -> method == null || method.equalsIgnoreCase(r.getMethod()))
                .filter(r -> statusCode == null || statusCode == r.getStatusCode())
                .filter(r -> startTime == null || (r.getTimestamp() != null && !r.getTimestamp().isBefore(startTime)))
                .filter(r -> endTime == null || (r.getTimestamp() != null && !r.getTimestamp().isAfter(endTime)))
                .collect(Collectors.toList());
    }

    // ==================== 删除与清空 ====================

    /**
     * 删除指定 ID 的历史记录。
     *
     * @param id 记录 ID
     * @return 是否删除成功
     */
    public boolean deleteHistory(String id) {
        HistoryStore historyStore = loadStore();
        boolean removed = historyStore.getRecords().removeIf(r -> id.equals(r.getId()));
        if (removed) {
            saveStore(historyStore);
            log.debug("删除历史记录: id={}", id);
        }
        return removed;
    }

    /**
     * 清空所有历史记录。
     */
    public void clearHistory() {
        HistoryStore historyStore = new HistoryStore();
        historyStore.setLimit(properties.getHistoryLimit());
        saveStore(historyStore);
        log.info("已清空所有历史记录");
    }

    /**
     * 获取历史记录总数。
     *
     * @return 记录数量
     */
    public int getHistoryCount() {
        return loadStore().getRecords().size();
    }

    // ==================== 内部方法 ====================

    /**
     * 从 RequestConfig + ResponseData 构建 HistoryRecord。
     */
    private HistoryRecord buildRecord(RequestConfig config, ResponseData response) {
        HistoryRecord record = new HistoryRecord();
        record.setId(UUID.randomUUID().toString());
        record.setTimestamp(LocalDateTime.now());

        // 请求信息
        record.setMethod(config.getMethod());
        record.setUrl(config.getUrl());
        record.setName(generateName(config));
        record.setParams(copyParams(config.getParams()));
        record.setHeaders(copyHeaders(config.getHeaders()));
        record.setBody(config.getBody());
        record.setAuth(config.getAuth());

        // 响应信息
        record.setStatusCode(response.getStatusCode());
        record.setStatusText(response.getStatusText());
        record.setResponseHeaders(response.getHeaders());
        record.setResponseBody(truncateBody(response.getBody()));
        record.setResponseContentType(response.getContentType());
        record.setResponseSize(response.getSize());

        // 性能指标
        TimingMetrics timing = response.getTiming();
        if (timing != null) {
            record.setResponseTime(timing.getTotalMs());
            record.setTtfb(timing.getTtfbMs());
        }

        // 错误信息记录到 statusText
        if (response.getError() != null) {
            record.setStatusText(response.getError());
        }

        return record;
    }

    /**
     * 从 URL 生成默认请求名称(取 path 最后一段)。
     */
    private String generateName(RequestConfig config) {
        String url = config.getUrl();
        if (url == null || url.isBlank()) {
            return config.getMethod() + " Request";
        }
        // 去掉 query string
        int qIdx = url.indexOf('?');
        if (qIdx > 0) {
            url = url.substring(0, qIdx);
        }
        // 取最后一段 path
        int sIdx = url.lastIndexOf('/');
        if (sIdx >= 0 && sIdx < url.length() - 1) {
            return url.substring(sIdx + 1);
        }
        return url;
    }

    /**
     * 截断过长的响应体。
     */
    private String truncateBody(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= MAX_BODY_LENGTH) {
            return body;
        }
        log.debug("响应体超过 1MB,已截断: originalSize={}", body.length());
        return body.substring(0, MAX_BODY_LENGTH) + "\n... [truncated]";
    }

    /**
     * 自动裁剪历史记录,保留最新的 limit 条。
     */
    private void trimHistory(HistoryStore historyStore) {
        int limit = properties.getHistoryLimit();
        List<HistoryRecord> records = historyStore.getRecords();
        if (records.size() > limit) {
            // 按时间排序,保留最新的 limit 条
            records.sort(Comparator.comparing(HistoryRecord::getTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            int toRemove = records.size() - limit;
            records.subList(0, toRemove).clear();
            log.debug("裁剪历史记录: removed={}, remaining={}", toRemove, records.size());
        }
    }

    /**
     * 按关键词匹配(检查 URL、Method、名称)。
     */
    private boolean matchesKeyword(HistoryRecord record, String lowerKeyword) {
        return containsIgnoreCase(record.getUrl(), lowerKeyword)
                || containsIgnoreCase(record.getMethod(), lowerKeyword)
                || containsIgnoreCase(record.getName(), lowerKeyword);
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword);
    }

    private List<QueryParam> copyParams(List<QueryParam> src) {
        List<QueryParam> result = new ArrayList<>();
        if (src != null) {
            for (QueryParam p : src) {
                QueryParam copy = new QueryParam();
                copy.setKey(p.getKey());
                copy.setValue(p.getValue());
                copy.setEnabled(p.isEnabled());
                copy.setDescription(p.getDescription());
                result.add(copy);
            }
        }
        return result;
    }

    private List<Header> copyHeaders(List<Header> src) {
        List<Header> result = new ArrayList<>();
        if (src != null) {
            for (Header h : src) {
                Header copy = new Header();
                copy.setKey(h.getKey());
                copy.setValue(h.getValue());
                copy.setEnabled(h.isEnabled());
                copy.setDescription(h.getDescription());
                result.add(copy);
            }
        }
        return result;
    }

    private HistoryStore loadStore() {
        HistoryStore store = this.store.read(HISTORY_FILE, HistoryStore.class);
        if (store == null) {
            store = new HistoryStore();
            store.setLimit(properties.getHistoryLimit());
        }
        return store;
    }

    private void saveStore(HistoryStore historyStore) {
        this.store.write(HISTORY_FILE, historyStore);
    }
}
