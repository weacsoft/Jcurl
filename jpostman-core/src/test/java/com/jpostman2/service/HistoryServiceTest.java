package com.jpostman2.service;

import com.jpostman2.config.AppConfig;
import com.jpostman2.model.HistoryRecord;
import com.jpostman2.model.dto.RequestConfig;
import com.jpostman2.model.dto.ResponseData;
import com.jpostman2.model.dto.TimingMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HistoryService 集成测试 — 验证历史记录的增删查搜与裁剪逻辑。
 */
@SpringBootTest(classes = AppConfig.class)
class HistoryServiceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 使用较小的 historyLimit 方便测试裁剪
        registry.add("jpostman2.data-dir", () -> tempDir.toString());
        registry.add("jpostman2.history-limit", () -> "5");
    }

    @Autowired
    private HistoryService historyService;

    @BeforeEach
    void clearBefore() {
        historyService.clearHistory();
    }

    @AfterEach
    void clearAfter() {
        historyService.clearHistory();
    }

    /** 创建测试用 RequestConfig */
    private RequestConfig createConfig(String method, String url) {
        RequestConfig config = new RequestConfig();
        config.setMethod(method);
        config.setUrl(url);
        return config;
    }

    /** 创建测试用 ResponseData */
    private ResponseData createResponse(int statusCode, String body, long totalMs) {
        ResponseData response = new ResponseData();
        response.setStatusCode(statusCode);
        response.setBody(body);
        response.setSize(body != null ? body.length() : 0);
        TimingMetrics timing = new TimingMetrics();
        timing.setTotalMs(totalMs);
        timing.setTtfbMs(totalMs / 2);
        response.setTiming(timing);
        return response;
    }

    /** 记录一条历史 */
    private HistoryRecord recordEntry(String method, String url, int statusCode, String body) {
        return historyService.record(createConfig(method, url), createResponse(statusCode, body, 100));
    }

    // ==================== 记录与列举 ====================

    @Nested
    @DisplayName("记录与列举")
    class RecordAndListTest {

        @Test
        @DisplayName("记录后可列举出来")
        void shouldRecordAndList() {
            HistoryRecord record = recordEntry("GET", "https://api.example.com/users", 200, "{}");

            List<HistoryRecord> list = historyService.listHistory();
            assertEquals(1, list.size());
            assertEquals(record.getId(), list.get(0).getId());
            assertEquals("GET", list.get(0).getMethod());
            assertEquals("https://api.example.com/users", list.get(0).getUrl());
            assertEquals(200, list.get(0).getStatusCode());
        }

        @Test
        @DisplayName("按 ID 获取历史记录")
        void shouldGetById() {
            HistoryRecord record = recordEntry("POST", "https://api.example.com/create", 201, "{\"id\":1}");

            HistoryRecord found = historyService.getHistory(record.getId());
            assertNotNull(found);
            assertEquals("POST", found.getMethod());
            assertEquals(201, found.getStatusCode());
            assertEquals("{\"id\":1}", found.getResponseBody());
        }

        @Test
        @DisplayName("记录按时间倒序排列(最新在前)")
        void shouldSortByTimeDesc() throws InterruptedException {
            recordEntry("GET", "/api/first", 200, "");
            Thread.sleep(10);
            recordEntry("GET", "/api/second", 200, "");
            Thread.sleep(10);
            recordEntry("GET", "/api/third", 200, "");

            List<HistoryRecord> list = historyService.listHistory();
            assertEquals(3, list.size());
            assertEquals("/api/third", list.get(0).getUrl());
            assertEquals("/api/first", list.get(2).getUrl());
        }

        @Test
        @DisplayName("记录性能指标")
        void shouldRecordTimingMetrics() {
            RequestConfig config = createConfig("GET", "/api/test");
            ResponseData response = createResponse(200, "ok", 250);
            response.getTiming().setTtfbMs(120);

            HistoryRecord record = historyService.record(config, response);

            assertEquals(250, record.getResponseTime());
            assertEquals(120, record.getTtfb());
        }

        @Test
        @DisplayName("从 URL 生成默认名称")
        void shouldGenerateNameFromUrl() {
            HistoryRecord record = recordEntry("GET", "https://api.example.com/users?page=1", 200, "");
            assertEquals("users", record.getName());
        }
    }

    // ==================== 搜索 ====================

    @Nested
    @DisplayName("搜索")
    class SearchTest {

        @Test
        @DisplayName("按 URL 关键词搜索")
        void shouldSearchByUrl() {
            recordEntry("GET", "https://api.example.com/users", 200, "");
            recordEntry("POST", "https://api.example.com/orders", 201, "");

            List<HistoryRecord> result = historyService.searchHistory("users");
            assertEquals(1, result.size());
            assertTrue(result.get(0).getUrl().contains("users"));
        }

        @Test
        @DisplayName("按 Method 搜索")
        void shouldSearchByMethod() {
            recordEntry("GET", "/api/a", 200, "");
            recordEntry("POST", "/api/b", 201, "");
            recordEntry("GET", "/api/c", 200, "");

            List<HistoryRecord> result = historyService.searchHistory("POST");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("搜索不区分大小写")
        void shouldSearchCaseInsensitive() {
            recordEntry("GET", "/api/users", 200, "");

            List<HistoryRecord> result = historyService.searchHistory("USERS");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("空关键词返回全部")
        void shouldReturnAllForEmptyKeyword() {
            recordEntry("GET", "/api/a", 200, "");
            recordEntry("POST", "/api/b", 201, "");

            assertEquals(2, historyService.searchHistory("").size());
            assertEquals(2, historyService.searchHistory(null).size());
        }
    }

    // ==================== 过滤 ====================

    @Nested
    @DisplayName("过滤")
    class FilterTest {

        @Test
        @DisplayName("按 Method 过滤")
        void shouldFilterByMethod() {
            recordEntry("GET", "/api/a", 200, "");
            recordEntry("POST", "/api/b", 201, "");
            recordEntry("GET", "/api/c", 200, "");

            assertEquals(2, historyService.filterHistory("GET", null, null, null).size());
            assertEquals(1, historyService.filterHistory("POST", null, null, null).size());
        }

        @Test
        @DisplayName("按状态码过滤")
        void shouldFilterByStatusCode() {
            recordEntry("GET", "/api/a", 200, "");
            recordEntry("POST", "/api/b", 404, "");
            recordEntry("GET", "/api/c", 500, "");

            assertEquals(1, historyService.filterHistory(null, 200, null, null).size());
            assertEquals(1, historyService.filterHistory(null, 404, null, null).size());
        }

        @Test
        @DisplayName("按时间范围过滤")
        void shouldFilterByTimeRange() throws InterruptedException {
            recordEntry("GET", "/api/old", 200, "");
            Thread.sleep(20);
            LocalDateTime mid = LocalDateTime.now();
            Thread.sleep(20);
            recordEntry("GET", "/api/new", 200, "");

            // 只查 mid 之后的
            List<HistoryRecord> afterMid = historyService.filterHistory(null, null, mid, null);
            assertEquals(1, afterMid.size());
            assertEquals("/api/new", afterMid.get(0).getUrl());
        }

        @Test
        @DisplayName("组合过滤")
        void shouldFilterWithMultipleConditions() {
            recordEntry("GET", "/api/a", 200, "");
            recordEntry("POST", "/api/b", 201, "");
            recordEntry("GET", "/api/c", 200, "");

            List<HistoryRecord> result = historyService.filterHistory("GET", 200, null, null);
            assertEquals(2, result.size());
        }
    }

    // ==================== 删除与清空 ====================

    @Nested
    @DisplayName("删除与清空")
    class DeleteTest {

        @Test
        @DisplayName("按 ID 删除单条记录")
        void shouldDeleteById() {
            HistoryRecord r1 = recordEntry("GET", "/api/a", 200, "");
            recordEntry("GET", "/api/b", 200, "");

            assertTrue(historyService.deleteHistory(r1.getId()));
            assertEquals(1, historyService.getHistoryCount());
            assertNull(historyService.getHistory(r1.getId()));
        }

        @Test
        @DisplayName("清空所有历史记录")
        void shouldClearAll() {
            recordEntry("GET", "/api/a", 200, "");
            recordEntry("GET", "/api/b", 200, "");

            historyService.clearHistory();

            assertEquals(0, historyService.getHistoryCount());
            assertTrue(historyService.listHistory().isEmpty());
        }
    }

    // ==================== 自动裁剪 ====================

    @Nested
    @DisplayName("自动裁剪")
    class TrimTest {

        @Test
        @DisplayName("超过上限时自动裁剪最旧记录")
        void shouldTrimWhenExceedLimit() throws InterruptedException {
            // limit = 5
            for (int i = 0; i < 5; i++) {
                recordEntry("GET", "/api/" + i, 200, "");
                Thread.sleep(5);
            }
            assertEquals(5, historyService.getHistoryCount());

            // 添加第 6 条,应裁剪到 5 条
            Thread.sleep(5);
            recordEntry("GET", "/api/new", 200, "");

            assertEquals(5, historyService.getHistoryCount());

            List<HistoryRecord> list = historyService.listHistory();
            // 最新的应该是 /api/new
            assertEquals("/api/new", list.get(0).getUrl());
            // /api/0 应该已被裁剪
            assertTrue(list.stream().noneMatch(r -> "/api/0".equals(r.getUrl())));
        }
    }

    // ==================== 响应体截断 ====================

    @Nested
    @DisplayName("响应体截断")
    class TruncateTest {

        @Test
        @DisplayName("响应体超过 1MB 时截断")
        void shouldTruncateLargeBody() {
            // 构造超过 1MB 的响应体
            StringBuilder largeBody = new StringBuilder();
            for (int i = 0; i < 1024 * 1024 + 100; i++) {
                largeBody.append("x");
            }

            HistoryRecord record = recordEntry("GET", "/api/large", 200, largeBody.toString());

            assertTrue(record.getResponseBody().length() < largeBody.length());
            assertTrue(record.getResponseBody().contains("[truncated]"));
        }

        @Test
        @DisplayName("正常大小的响应体不截断")
        void shouldNotTruncateNormalBody() {
            String body = "{\"message\":\"hello world\"}";
            HistoryRecord record = recordEntry("GET", "/api/normal", 200, body);

            assertEquals(body, record.getResponseBody());
        }
    }

    // ==================== 错误响应记录 ====================

    @Nested
    @DisplayName("错误响应")
    class ErrorResponseTest {

        @Test
        @DisplayName("请求失败时记录错误信息")
        void shouldRecordErrorResponse() {
            RequestConfig config = createConfig("GET", "https://unreachable.example.com");
            ResponseData response = new ResponseData();
            response.setStatusCode(0);
            response.setError("Connection refused");
            response.setTiming(new TimingMetrics());

            HistoryRecord record = historyService.record(config, response);

            assertEquals(0, record.getStatusCode());
            assertEquals("Connection refused", record.getStatusText());
        }
    }
}
