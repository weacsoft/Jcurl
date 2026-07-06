package com.jpostman2.service;

import com.jpostman2.config.AppConfig;
import com.jpostman2.model.component.AuthConfig;
import com.jpostman2.model.component.FormItem;
import com.jpostman2.model.component.QueryParam;
import com.jpostman2.model.component.RequestBody;
import com.jpostman2.model.dto.RequestConfig;
import com.jpostman2.model.dto.ResponseData;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpEngineService 测试 — 用 MockWebServer 验证各方法/Body类型/认证/性能指标。
 */
@SpringBootTest(classes = AppConfig.class)
class HttpEngineServiceTest {

    @Autowired
    private HttpEngineService httpEngine;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldSendGetRequestWithQueryParams() throws Exception {
        server.enqueue(new MockResponse().setBody("hello world").setResponseCode(200));

        RequestConfig config = new RequestConfig("GET", server.url("/api/test").toString());
        config.getParams().add(new QueryParam("page", "1"));
        config.getParams().add(new QueryParam("size", "10"));

        ResponseData response = httpEngine.execute(config, "test-get");

        assertEquals(200, response.getStatusCode());
        assertEquals("hello world", response.getBody());
        assertTrue(response.getSize() > 0);

        // 验证收到的请求
        RecordedRequest recorded = server.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertTrue(recorded.getPath().contains("page=1"));
        assertTrue(recorded.getPath().contains("size=10"));
    }

    @Test
    void shouldSendPostWithRawJsonBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"result\":\"ok\"}").setResponseCode(201));

        RequestConfig config = new RequestConfig("POST", server.url("/api/users").toString());
        config.getHeaders().add(new com.jpostman2.model.component.Header("Content-Type", "application/json"));
        config.setBody(new RequestBody("raw"));
        config.getBody().setRawType("json");
        config.getBody().setContent("{\"name\":\"test\",\"age\":18}");

        ResponseData response = httpEngine.execute(config, "test-post-json");

        assertEquals(201, response.getStatusCode());
        assertEquals("{\"result\":\"ok\"}", response.getBody());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("{\"name\":\"test\",\"age\":18}", recorded.getBody().readUtf8());
        assertTrue(recorded.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    void shouldSendPostWithFormUrlEncoded() throws Exception {
        server.enqueue(new MockResponse().setBody("created").setResponseCode(200));

        RequestConfig config = new RequestConfig("POST", server.url("/api/form").toString());
        config.setBody(new RequestBody("x-www-form-urlencoded"));
        config.getBody().getFormItems().add(new FormItem("username", "admin"));
        config.getBody().getFormItems().add(new FormItem("password", "123456"));

        ResponseData response = httpEngine.execute(config, "test-form");

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("username=admin"));
        assertTrue(body.contains("password=123456"));
        assertTrue(recorded.getHeader("Content-Type").contains("x-www-form-urlencoded"));
    }

    @Test
    void shouldSendPostWithMultipartFormData() throws Exception {
        server.enqueue(new MockResponse().setBody("uploaded").setResponseCode(200));

        // 创建临时文件用于上传测试
        Path tempFile = Files.createTempFile("test-upload", ".txt");
        Files.writeString(tempFile, "file content here");

        try {
            RequestConfig config = new RequestConfig("POST", server.url("/api/upload").toString());
            config.setBody(new RequestBody("form-data"));
            config.getBody().getFormItems().add(new FormItem("name", "myfile"));
            config.getBody().getFormItems().add(FormItem.file("file", tempFile.toString()));

            ResponseData response = httpEngine.execute(config, "test-multipart");

            assertEquals(200, response.getStatusCode());

            RecordedRequest recorded = server.takeRequest();
            String body = recorded.getBody().readUtf8();
            assertTrue(body.contains("name=\"name\""));
            assertTrue(body.contains("myfile"));
            assertTrue(body.contains("name=\"file\""));
            assertTrue(body.contains("file content here"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void shouldSendPutRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("updated"));

        RequestConfig config = new RequestConfig("PUT", server.url("/api/users/1").toString());
        config.setBody(new RequestBody("raw"));
        config.getBody().setRawType("json");
        config.getBody().setContent("{\"name\":\"updated\"}");

        ResponseData response = httpEngine.execute(config, "test-put");

        assertEquals(200, response.getStatusCode());
        assertEquals("updated", response.getBody());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("PUT", recorded.getMethod());
    }

    @Test
    void shouldSendDeleteRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        RequestConfig config = new RequestConfig("DELETE", server.url("/api/users/1").toString());

        ResponseData response = httpEngine.execute(config, "test-delete");

        assertEquals(204, response.getStatusCode());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
    }

    @Test
    void shouldApplyBearerTokenAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig("GET", server.url("/api/secure").toString());
        AuthConfig auth = new AuthConfig("bearer");
        auth.setToken("my-secret-token");
        config.setAuth(auth);

        httpEngine.execute(config, "test-bearer");

        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer my-secret-token", recorded.getHeader("Authorization"));
    }

    @Test
    void shouldApplyBasicAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig("GET", server.url("/api/secure").toString());
        AuthConfig auth = new AuthConfig("basic");
        auth.setUsername("admin");
        auth.setPassword("pass123");
        config.setAuth(auth);

        httpEngine.execute(config, "test-basic");

        RecordedRequest recorded = server.takeRequest();
        String expected = "Basic " + Base64.getEncoder().encodeToString("admin:pass123".getBytes());
        assertEquals(expected, recorded.getHeader("Authorization"));
    }

    @Test
    void shouldApplyApiKeyInHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig("GET", server.url("/api/secure").toString());
        AuthConfig auth = new AuthConfig("apikey");
        auth.setKey("X-API-Key");
        auth.setValue("key123");
        auth.setAddTo("header");
        config.setAuth(auth);

        httpEngine.execute(config, "test-apikey-header");

        RecordedRequest recorded = server.takeRequest();
        assertEquals("key123", recorded.getHeader("X-API-Key"));
    }

    @Test
    void shouldApplyApiKeyInQuery() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig("GET", server.url("/api/secure").toString());
        AuthConfig auth = new AuthConfig("apikey");
        auth.setKey("api_key");
        auth.setValue("querykey456");
        auth.setAddTo("query");
        config.setAuth(auth);

        httpEngine.execute(config, "test-apikey-query");

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getPath().contains("api_key=querykey456"));
    }

    @Test
    void shouldCollectTimingMetrics() throws Exception {
        server.enqueue(new MockResponse().setBody("timing test").setResponseCode(200));

        RequestConfig config = new RequestConfig("GET", server.url("/api/test").toString());

        ResponseData response = httpEngine.execute(config, "test-timing");

        assertNotNull(response.getTiming());
        assertTrue(response.getTiming().getTotalMs() >= 0, "总耗时应 >= 0");
        assertTrue(response.getTiming().getTtfbMs() >= 0, "TTFB 应 >= 0");
    }

    @Test
    void shouldHandleErrorResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        RequestConfig config = new RequestConfig("GET", server.url("/api/error").toString());

        ResponseData response = httpEngine.execute(config, "test-error");

        assertEquals(500, response.getStatusCode());
        assertEquals("Internal Server Error", response.getBody());
        assertTrue(response.isSuccess());
    }

    @Test
    void shouldHandleConnectionFailure() throws Exception {
        // 关闭服务器后请求,模拟连接失败
        int port = server.getPort();
        server.shutdown();

        RequestConfig config = new RequestConfig("GET", "http://127.0.0.1:" + port + "/api/test");

        ResponseData response = httpEngine.execute(config, "test-failure");

        assertNotNull(response.getError());
        assertFalse(response.isSuccess());
    }

    @Test
    void shouldParseResponseHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("ok")
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setHeader("X-Custom-Header", "custom-value"));

        RequestConfig config = new RequestConfig("GET", server.url("/api/test").toString());

        ResponseData response = httpEngine.execute(config, "test-headers");

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getHeaders().size() >= 2);
        boolean foundCustom = response.getHeaders().stream()
                .anyMatch(h -> "X-Custom-Header".equals(h.getKey()) && "custom-value".equals(h.getValue()));
        assertTrue(foundCustom, "应能解析自定义响应头");
        assertTrue(response.getContentType().contains("application/json"));
    }

    @Test
    void shouldCancelRequest() throws Exception {
        // 不 enqueue 任何响应,MockWebServer 会让请求一直等待,确保取消时请求仍在进行中
        RequestConfig config = new RequestConfig("GET", server.url("/api/slow").toString());

        // 异步发起请求
        var future = httpEngine.executeAsync(config, "test-cancel");

        // 等待确保请求已发出并在进行中
        Thread.sleep(500);

        // 取消请求
        boolean cancelled = httpEngine.cancel("test-cancel");
        assertTrue(cancelled, "应能取消进行中的请求");

        ResponseData response = future.get(5, TimeUnit.SECONDS);
        assertTrue(response.isCancelled(), "响应应标记为已取消");
    }
}
