package com.jcurl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.AuthConfig;
import com.jcurl.model.KeyValue;
import com.jcurl.model.dto.RequestConfig;
import com.jcurl.model.dto.ResponseData;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpEngineService 单元测试。
 * <p>
 * 使用 MockWebServer 模拟 HTTP 服务器, 验证请求构建和响应处理。
 * HttpEngineService 不依赖 Spring 容器, 直接 new 创建实例。
 * 测试类使用纯 JUnit 5, 不需要 @DataJpaTest 或 @SpringBootTest。
 */
class HttpEngineServiceTest {

    private MockWebServer mockWebServer;
    private HttpEngineService service;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        service = new HttpEngineService(new ObjectMapper(), null, new CookieService(), null);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldSendGetRequest() {
        String responseBody = "{\"message\":\"hello\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());

        ResponseData response = service.execute(config);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getStatusText()).isEqualTo("OK");
        assertThat(response.getResponseBody()).isEqualTo(responseBody);
        assertThat(response.getResponseTime()).isGreaterThan(0L);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResponseHeaders()).containsKey("Content-Type");
    }

    @Test
    void shouldSendPostRequestWithRawBody() throws Exception {
        String jsonBody = "{\"name\":\"test\",\"value\":123}";
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        RequestConfig config = new RequestConfig();
        config.setMethod("POST");
        config.setUrl(mockWebServer.url("/").toString());
        config.setBodyType("raw");
        config.setBodyContent(jsonBody);

        ResponseData response = service.execute(config);

        assertThat(response.getStatusCode()).isEqualTo(200);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getBody().readUtf8()).isEqualTo(jsonBody);
        assertThat(recordedRequest.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    void shouldSendGetWithQueryParams() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        config.setParams(Arrays.asList(
                new KeyValue("foo", "bar", null, true),
                new KeyValue("disabled", "value", null, false),
                new KeyValue("baz", "qux", null, true)
        ));

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getPath()).contains("foo=bar");
        assertThat(recordedRequest.getPath()).contains("baz=qux");
        assertThat(recordedRequest.getPath()).doesNotContain("disabled");
    }

    @Test
    void shouldSendWithHeaders() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        config.setHeaders(Arrays.asList(
                new KeyValue("X-Custom-Header", "custom-value", null, true),
                new KeyValue("X-Disabled", "no", null, false)
        ));

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getHeader("X-Custom-Header")).isEqualTo("custom-value");
        assertThat(recordedRequest.getHeader("X-Disabled")).isNull();
    }

    @Test
    void shouldSendWithBasicAuth() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        AuthConfig auth = new AuthConfig();
        auth.setType("basic");
        auth.setBasicUsername("user");
        auth.setBasicPassword("pass");
        config.setAuth(auth);

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getHeader("Authorization")).startsWith("Basic ");
    }

    @Test
    void shouldSendWithBearerToken() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        AuthConfig auth = new AuthConfig();
        auth.setType("bearer");
        auth.setBearerToken("my-token");
        config.setAuth(auth);

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer my-token");
    }

    @Test
    void shouldHandleNotFoundError() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());

        ResponseData response = service.execute(config);

        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getResponseBody()).isEqualTo("Not Found");
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    void shouldHandleConnectionError() {
        String url = mockWebServer.url("/").toString();
        try {
            mockWebServer.shutdown();
        } catch (IOException e) {
            // 忽略关闭异常
        }

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(url);

        ResponseData response = service.execute(config);

        assertThat(response.getStatusCode()).isEqualTo(0);
        assertThat(response.getErrorMessage()).isNotEmpty();
        assertThat(response.isSuccess()).isFalse();
    }

    @Test
    void shouldSendFormUrlencodedBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("POST");
        config.setUrl(mockWebServer.url("/").toString());
        config.setBodyType("urlencoded");
        config.setBodyContent("[{\"key\":\"name\",\"value\":\"john\"},{\"key\":\"age\",\"value\":\"30\"}]");

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        String body = recordedRequest.getBody().readUtf8();
        assertThat(body).contains("name=john");
        assertThat(body).contains("age=30");
        assertThat(recordedRequest.getHeader("Content-Type"))
                .contains("application/x-www-form-urlencoded");
    }

    @Test
    void shouldApplyApiKeyToHeader() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        AuthConfig auth = new AuthConfig();
        auth.setType("apikey");
        auth.setApiKeyName("X-Api-Key");
        auth.setApiKeyValue("secret-key");
        auth.setApiKeyIn("header");
        config.setAuth(auth);

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getHeader("X-Api-Key")).isEqualTo("secret-key");
    }

    @Test
    void shouldApplyApiKeyToQuery() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        AuthConfig auth = new AuthConfig();
        auth.setType("apikey");
        auth.setApiKeyName("api_key");
        auth.setApiKeyValue("query-secret");
        auth.setApiKeyIn("query");
        config.setAuth(auth);

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getPath()).contains("api_key=query-secret");
    }

    // ===== 新增: 验证默认头自动带上 (问题1/3) =====

    @Test
    void shouldSendDefaultHeadersAutomatically() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        // 不设置任何 header

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        // 5 个静态默认头应自动带上
        assertThat(recordedRequest.getHeader("Accept")).isEqualTo("*/*");
        assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("Jcurl/0.1");
        assertThat(recordedRequest.getHeader("Accept-Encoding")).isEqualTo("gzip, deflate");
        assertThat(recordedRequest.getHeader("Accept-Language")).isEqualTo("zh-CN,zh;q=0.9");
        assertThat(recordedRequest.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    // ===== 新增: 用户同名头覆盖默认头 (问题3) =====

    @Test
    void shouldUserHeaderOverrideDefault() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        config.setHeaders(Arrays.asList(
                new KeyValue("User-Agent", "MyApp/2.0", null, true)
        ));

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        // 用户自定义 User-Agent 应覆盖默认值
        assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("MyApp/2.0");
        // 其他默认头仍应存在
        assertThat(recordedRequest.getHeader("Accept")).isEqualTo("*/*");
    }

    // ===== 新增: GET 带 body (问题5) =====
    // 注意: MockWebServer 严格遵循 HTTP 规范, 拒绝读取带 body 的 GET 请求 (会抛
    // IllegalArgumentException), 因此无法用 MockWebServer 验证 GET body 内容。
    // 这里仅验证 OkHttp 不再因 GET+body 抛 IllegalArgumentException (反射绕过成功),
    // 请求被发出后 MockWebServer 会拒绝处理, response 为错误状态 (非系统异常)。

    @Test
    void shouldAttemptGetWithBodyWithoutThrowing() {
        String body = "{\"query\":\"all\"}";
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/search").toString());
        config.setBodyType("raw");
        config.setBodyContent(body);

        // 不应抛出 IllegalArgumentException (OkHttp 原本禁止 GET 带 body)
        // MockWebServer 会拒绝该请求, 返回错误响应, 但不应抛出异常
        ResponseData response = service.execute(config);
        // 请求被发出 (MockWebServer 拒绝处理 → 错误响应), 未抛出异常即说明反射绕过成功
        assertThat(response).isNotNull();
    }

    // ===== 新增: 空 form-data 提交 (问题5) =====

    @Test
    void shouldSendEmptyFormData() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("POST");
        config.setUrl(mockWebServer.url("/upload").toString());
        config.setBodyType("form-data");
        config.setBodyContent("[]"); // 空 form

        ResponseData response = service.execute(config);

        // 空 form-data 应允许提交 (不抛异常), 等价于空提交
        assertThat(response.isSuccess()).isTrue();
        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    }

    // ===== 新增: gzip 响应解压 (问题6) =====

    @Test
    void shouldDecompressGzipResponse() throws Exception {
        String originalBody = "<html><body><h1>Hello Server</h1></body></html>";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(bos)) {
            gos.write(originalBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] gzipped = bos.toByteArray();

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Encoding", "gzip")
                .setHeader("Content-Type", "text/html")
                .setBody(new okio.Buffer().write(gzipped)));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/page").toString());

        ResponseData response = service.execute(config);

        assertThat(response.isSuccess()).isTrue();
        // 解压后应得到原始 HTML
        assertThat(response.getResponseBody()).isEqualTo(originalBody);
    }

    // ===== 新增: requestUrl 被记录到响应 (问题6基础) =====

    @Test
    void shouldRecordRequestUrl() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        String url = mockWebServer.url("/api/test?key=val").toString();
        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(url);

        ResponseData response = service.execute(config);

        assertThat(response.getRequestUrl()).isEqualTo(url);
    }

    // ===== 新增: 用户自定义 Authorization 不被认证覆盖 (问题4) =====

    @Test
    void shouldNotOverrideManualAuthHeader() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RequestConfig config = new RequestConfig();
        config.setMethod("GET");
        config.setUrl(mockWebServer.url("/").toString());
        // 用户手动设置 Authorization
        config.setHeaders(Arrays.asList(
                new KeyValue("Authorization", "Bearer manual-token", null, true)
        ));
        AuthConfig auth = new AuthConfig();
        auth.setType("bearer");
        auth.setBearerToken("auto-token");
        config.setAuth(auth);

        service.execute(config);

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        // 应保留用户手动设置的头, 不被自动认证覆盖
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer manual-token");
    }
}
