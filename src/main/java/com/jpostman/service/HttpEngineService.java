package com.jpostman.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman.model.AuthConfig;
import com.jpostman.model.KeyValue;
import com.jpostman.model.dto.RequestConfig;
import com.jpostman.model.dto.ResponseData;
import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.EventListener;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * HTTP 请求引擎 — 基于 OkHttp 封装的请求执行服务。
 * <p>
 * 接收 {@link RequestConfig} 配置, 构建并执行 HTTP 请求, 返回 {@link ResponseData}。
 * <p>
 * 特性:
 * <ul>
 *   <li>信任所有 SSL 证书 (适用于本地调试, 面对自签名证书)</li>
 *   <li>支持 query 参数、headers、多种 body 类型 (none/form-data/urlencoded/raw/binary)</li>
 *   <li>支持多种认证方式 (none/basic/bearer/apikey), 通过 {@link AuthConfig} 配置</li>
 *   <li>性能指标采集: DNS 解析时间、TCP 连接时间、首字节时间 TTFB (基于 OkHttp {@link EventListener})</li>
 *   <li>请求取消: {@link #cancelCurrentRequest()}</li>
 *   <li>响应体限制读取 10MB, 同时保留原始字节 ({@code responseBytes}) 用于图片预览</li>
 *   <li>网络错误时 statusCode=0, errorMessage 填充错误信息</li>
 *   <li>请求执行后自动存储 Set-Cookie 并记录历史</li>
 * </ul>
 * <p>
 * 本步骤不实现变量替换(Step 5)、插件钩子(Step 12)。
 */
@Service
public class HttpEngineService {

    private static final Logger log = LoggerFactory.getLogger(HttpEngineService.class);

    /** 响应体最大读取字节数 (10MB) */
    private static final long MAX_RESPONSE_BYTES = 10L * 1024 * 1024;

    /** 默认 raw body Content-Type */
    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;
    private final OkHttpClient client;
    private final HistoryService historyService;
    private final CookieService cookieService;

    /** 默认请求头提供者 (核心层, 无状态), 负责自动默认头与用户头的合并。 */
    private final DefaultHeaderProvider defaultHeaderProvider = new DefaultHeaderProvider();

    /** 当前正在执行的请求 Call, 用于支持请求取消。volatile 保证多线程可见性。 */
    private volatile Call currentCall;

    public HttpEngineService(ObjectMapper objectMapper, HistoryService historyService,
                             CookieService cookieService) {
        this.objectMapper = objectMapper;
        this.historyService = historyService;
        this.cookieService = cookieService;
        this.client = buildClient();
    }

    /**
     * 取消当前正在执行的请求。
     * <p>
     * 若没有正在执行的请求则不做任何操作。取消后 {@link #execute(RequestConfig)} 会收到
     * IOException ("Canceled") 并将 statusCode 置为 0。
     */
    public void cancelCurrentRequest() {
        if (currentCall != null) {
            currentCall.cancel();
        }
    }

    /**
     * 构建 OkHttpClient: 30 秒超时 + 信任所有 SSL 证书。
     * <p>
     * SSL 配置失败时回退到默认 SSL (仍保持 30 秒超时)。该 client 作为基础 client,
     * 每次请求通过 {@code newBuilder()} 派生出带独立 {@link EventListener} 的子 client,
     * 以采集 per-call 性能指标 (复用同一连接池)。
     */
    private OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);

        try {
            X509TrustManager trustManager = createTrustAllManager();
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            log.warn("配置信任所有 SSL 证书失败, 回退到默认 SSL 配置", e);
        }

        return builder.build();
    }

    /**
     * 创建信任所有证书的 X509TrustManager。
     * <p>
     * 本地调试工具常面对自签名证书, 信任所有可避免 SSL 握手失败。
     */
    private X509TrustManager createTrustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 信任所有客户端证书
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 信任所有服务器证书
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * 执行 HTTP 请求。
     * <p>
     * 根据 {@link RequestConfig} 构建请求, 同步执行, 返回 {@link ResponseData}。
     * 网络错误时 statusCode=0, errorMessage 填充错误信息。
     *
     * @param config 请求配置, 不能为 null
     * @return 响应数据
     */
    public ResponseData execute(RequestConfig config) {
        ResponseData response = new ResponseData();

        if (config == null) {
            response.setErrorMessage("请求配置不能为空");
            return response;
        }

        // method 转大写处理
        String method = config.getMethod();
        if (method == null || method.trim().isEmpty()) {
            method = "GET";
        } else {
            method = method.trim().toUpperCase();
        }

        // URL 为空检查
        String url = config.getUrl();
        if (url == null || url.trim().isEmpty()) {
            response.setErrorMessage("请求 URL 不能为空");
            return response;
        }
        // 记录请求 URL, 供响应面板推导 <base href> 以渲染 HTML 中的相对资源
        response.setRequestUrl(url);

        // 构建请求
        Request request;
        try {
            request = buildRequest(config, method, url);
        } catch (IllegalArgumentException e) {
            response.setStatusCode(0);
            response.setErrorMessage(e.getMessage());
            return response;
        } catch (Exception e) {
            response.setStatusCode(0);
            response.setErrorMessage("请求构建失败: " + e.getMessage());
            return response;
        }

        // 性能指标采集: 为每个请求创建独立的 OkHttpClient (复用基础 client 的连接池与配置),
        // 通过 EventListener 记录 DNS 解析时间、TCP 连接时间、首字节时间 (TTFB) 到局部 Timing 容器。
        // 同时根据 RequestConfig.followRedirects 控制是否自动跟随重定向 (默认 true, 可关闭)。
        final Timing timing = new Timing();
        OkHttpClient perCallClient = client.newBuilder()
                .followRedirects(config.isFollowRedirects())
                .eventListener(new EventListener() {
                    @Override
                    public void dnsStart(Call call, String domain) {
                        timing.dnsStart = System.nanoTime();
                    }

                    @Override
                    public void dnsEnd(Call call, String domain, List<InetAddress> addressList) {
                        timing.dnsTime = (System.nanoTime() - timing.dnsStart) / 1_000_000L;
                    }

                    @Override
                    public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
                        timing.connectStart = System.nanoTime();
                    }

                    @Override
                    public void connectEnd(Call call, InetSocketAddress inetSocketAddress,
                                           Proxy proxy, Protocol protocol) {
                        timing.tcpConnectTime = (System.nanoTime() - timing.connectStart) / 1_000_000L;
                    }

                    @Override
                    public void responseHeadersStart(Call call) {
                        timing.firstByteTime = System.nanoTime();
                    }

                    @Override
                    public void responseHeadersEnd(Call call, Response resp) {
                        timing.ttfb = (System.nanoTime() - timing.firstByteTime) / 1_000_000L;
                    }
                })
                .build();

        // 执行请求
        long start = System.currentTimeMillis();
        currentCall = perCallClient.newCall(request);
        try (Response okResponse = currentCall.execute()) {
            response.setResponseTime(System.currentTimeMillis() - start);
            response.setStatusCode(okResponse.code());
            response.setStatusText(okResponse.message());
            response.setProtocolVersion(okResponse.protocol().toString());
            response.setDnsTime(timing.dnsTime);
            response.setTcpConnectTime(timing.tcpConnectTime);
            response.setTtfb(timing.ttfb);

            // 收集响应头
            // Set-Cookie 可能出现多次, 合并以 "\n" 分隔, 便于 CookieService 解析多个 cookie
            Map<String, String> headerMap = new HashMap<>();
            Headers headers = okResponse.headers();
            for (int i = 0; i < headers.size(); i++) {
                String name = headers.name(i);
                String value = headers.value(i);
                if ("Set-Cookie".equalsIgnoreCase(name)) {
                    String existing = headerMap.get("Set-Cookie");
                    headerMap.put("Set-Cookie", existing == null ? value : existing + "\n" + value);
                } else {
                    headerMap.put(name, value);
                }
            }
            response.setResponseHeaders(headerMap);

            // 读取响应体 (限制 10MB), 保留原始字节用于图片预览
            ResponseBody body = okResponse.body();
            if (body != null) {
                byte[] bytes = readBodyLimited(body.byteStream());
                // 由于默认发送 Accept-Encoding: gzip, deflate, OkHttp 不再自动解压,
                // 这里根据 Content-Encoding 自行解压, 保证响应体可读 (修复服务器场景下 HTML 预览空白)。
                String contentEncoding = getHeaderIgnoreCase(headerMap, "Content-Encoding");
                bytes = decompressIfNeeded(bytes, contentEncoding);
                response.setResponseBytes(bytes);
                response.setResponseBody(new String(bytes, StandardCharsets.UTF_8));
                response.setResponseSize(bytes.length);
            }
        } catch (IOException e) {
            response.setStatusCode(0);
            response.setErrorMessage("网络错误: " + e.getMessage());
            response.setResponseTime(System.currentTimeMillis() - start);
            response.setDnsTime(timing.dnsTime);
            response.setTcpConnectTime(timing.tcpConnectTime);
            response.setTtfb(timing.ttfb);
            log.warn("HTTP 请求失败: {}", e.getMessage());
        } finally {
            currentCall = null;
        }

        // 自动存储响应中的 Set-Cookie
        try {
            cookieService.storeFromResponse(config.getUrl(), response.getResponseHeaders());
        } catch (Exception e) {
            log.warn("存储 Cookie 失败", e);
        }

        // 记录历史 (如果 historyService 可用)
        if (historyService != null) {
            try {
                historyService.record(config, response);
            } catch (Exception e) {
                log.warn("记录历史失败, 不影响请求结果", e);
            }
        }

        return response;
    }

    /**
     * 构建 OkHttp Request。
     * <p>
     * 处理顺序: URL + query params → apikey in query → headers → auth → cookie → body → method。
     *
     * @param config 请求配置
     * @param method HTTP 方法 (已转大写)
     * @param url    请求 URL
     * @return OkHttp Request
     * @throws IOException 当 body JSON 解析失败或文件不存在时
     */
    private Request buildRequest(RequestConfig config, String method, String url) throws IOException {
        HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) {
            throw new IllegalArgumentException("无效的 URL: " + url);
        }
        HttpUrl.Builder urlBuilder = httpUrl.newBuilder();

        // 1. 拼接 query 参数 (enabled=true)
        List<KeyValue> params = config.getParams();
        if (params != null) {
            for (KeyValue param : params) {
                if (param.isEnabled() && param.getKey() != null) {
                    urlBuilder.addQueryParameter(param.getKey(),
                            param.getValue() != null ? param.getValue() : "");
                }
            }
        }

        // 认证配置 (统一从 AuthConfig 读取)
        AuthConfig auth = config.getAuth();

        // 2. apikey in=query 时添加到 URL query
        if (auth != null && !auth.isEmpty() && "apikey".equalsIgnoreCase(auth.getType())) {
            String in = auth.getApiKeyIn();
            if ("query".equalsIgnoreCase(in) && auth.getApiKeyName() != null) {
                urlBuilder.addQueryParameter(auth.getApiKeyName(),
                        auth.getApiKeyValue() != null ? auth.getApiKeyValue() : "");
            }
        }

        HttpUrl finalUrl = urlBuilder.build();
        Request.Builder requestBuilder = new Request.Builder().url(finalUrl);

        // 3. 合并默认头与用户头 —— 用户同名头覆盖默认头 (核心层统一决定"发送什么头")
        // 对于 form-data 和 urlencoded, OkHttp 的 RequestBody 会自动设置
        // Content-Type (含 boundary), 因此跳过合并结果中的 Content-Type 以避免冲突。
        String bodyType = config.getBodyType();
        boolean skipContentType = bodyType != null
                && ("form-data".equalsIgnoreCase(bodyType.trim())
                    || "urlencoded".equalsIgnoreCase(bodyType.trim()));

        List<KeyValue> autoHeaders = defaultHeaderProvider.computeAutoHeaders(bodyType, config.getRawContentType());
        List<KeyValue> effectiveHeaders = defaultHeaderProvider.mergeEffective(autoHeaders, config.getHeaders());
        Set<String> existingHeaderKeys = new HashSet<>();
        for (KeyValue header : effectiveHeaders) {
            if (header.getKey() == null) {
                continue;
            }
            if (skipContentType && "content-type".equalsIgnoreCase(header.getKey().trim())) {
                continue;
            }
            requestBuilder.addHeader(header.getKey(),
                    header.getValue() != null ? header.getValue() : "");
            existingHeaderKeys.add(header.getKey().trim().toLowerCase());
        }

        // 4. 认证处理 - header 方式 (basic / bearer / apikey in=header)
        // 若用户已手动设置同名头 (Authorization 或 apikey 名称), 则跳过自动认证, 避免重复。
        if (auth != null && !auth.isEmpty()) {
            String type = auth.getType().toLowerCase();
            if ("basic".equals(type)) {
                if (!existingHeaderKeys.contains("authorization")) {
                    String username = auth.getBasicUsername() != null ? auth.getBasicUsername() : "";
                    String password = auth.getBasicPassword() != null ? auth.getBasicPassword() : "";
                    requestBuilder.addHeader("Authorization", Credentials.basic(username, password));
                }
            } else if ("bearer".equals(type)) {
                if (!existingHeaderKeys.contains("authorization")) {
                    String token = auth.getBearerToken() != null ? auth.getBearerToken() : "";
                    requestBuilder.addHeader("Authorization", "Bearer " + token);
                }
            } else if ("apikey".equals(type)) {
                String in = auth.getApiKeyIn();
                if (in == null || "header".equalsIgnoreCase(in)) {
                    if (auth.getApiKeyName() != null
                            && !existingHeaderKeys.contains(auth.getApiKeyName().trim().toLowerCase())) {
                        requestBuilder.addHeader(auth.getApiKeyName(),
                                auth.getApiKeyValue() != null ? auth.getApiKeyValue() : "");
                    }
                }
                // cookie 模式暂不实现
            }
        }

        // 5. 自动添加 Cookie (用户未手动设置 Cookie 头时)
        String cookies = cookieService.getCookiesForUrl(url);
        if (cookies != null && !cookies.isEmpty() && !existingHeaderKeys.contains("cookie")) {
            requestBuilder.addHeader("Cookie", cookies);
        }

        // 6. 构建 body 并设置 method (GET/HEAD 带 body 时通过反射绕过 OkHttp 限制)
        RequestBody requestBody = buildBody(config, method);
        applyMethod(requestBuilder, method, requestBody);

        return requestBuilder.build();
    }

    /**
     * 根据 bodyType 构建请求体。
     * <p>
     * none/null: POST/PUT/PATCH 加空 body, 其余方法无 body。
     * form-data: MultipartBody (允许空表单, 等价于空提交)。
     * urlencoded: FormBody (允许空表单)。
     * raw: 原始文本, Content-Type 优先取 {@link RequestConfig#getRawContentType()},
     *      其次从 headers 解析, 默认 application/json。
     * binary: 文件, Content-Type 为 application/octet-stream。
     * <p>
     * 注意: 即便方法为 GET/HEAD, 只要用户显式选择了 body 类型 (非 none), 也构建 body,
     * 由 {@link #applyMethod} 通过反射设置 —— 这是 HTTP 测试工具应具备的能力。
     */
    private RequestBody buildBody(RequestConfig config, String method) throws IOException {
        String bodyType = config.getBodyType();
        String bodyContent = config.getBodyContent();

        if (bodyType == null || bodyType.trim().isEmpty() || "none".equalsIgnoreCase(bodyType.trim())) {
            // POST/PUT/PATCH 用空 body (OkHttp 要求非 null); 其余方法无 body
            boolean needsEmptyBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
            return needsEmptyBody ? RequestBody.create("", null) : null;
        }

        String normalized = bodyType.trim().toLowerCase();
        if ("form-data".equals(normalized)) {
            return buildMultipartBody(bodyContent);
        }
        if ("urlencoded".equals(normalized)) {
            return buildFormBody(bodyContent);
        }
        if ("raw".equals(normalized)) {
            return buildRawBody(config, bodyContent);
        }
        if ("binary".equals(normalized)) {
            return buildBinaryBody(bodyContent);
        }
        // 未知 bodyType, 按 none 处理
        boolean needsEmptyBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return needsEmptyBody ? RequestBody.create("", null) : null;
    }

    /**
     * 构建 multipart/form-data 请求体。
     * <p>
     * bodyContent 为 JSON 数组:
     * {@code [{"key":"k1","value":"v1","type":"text"},{"key":"k2","value":"/path/file","type":"file"}]}
     * <p>
     * type=file 时从路径读取文件; type=text (默认) 为普通表单字段。
     */
    private RequestBody buildMultipartBody(String bodyContent) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        int partCount = 0;
        if (bodyContent != null && !bodyContent.trim().isEmpty()) {
            List<Map<String, String>> parts = objectMapper.readValue(bodyContent,
                    new TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> part : parts) {
                String key = part.get("key");
                if (key == null || key.trim().isEmpty()) {
                    continue;
                }
                // 跳过 disabled 条目
                String enabledStr = part.get("enabled");
                if (enabledStr != null && !"true".equalsIgnoreCase(enabledStr)) {
                    continue;
                }
                String value = part.get("value");
                String type = part.get("type");
                if ("file".equalsIgnoreCase(type)) {
                    // 文件上传: 从路径读取文件
                    if (value != null && !value.trim().isEmpty()) {
                        File file = new File(value.trim());
                        if (file.exists() && file.isFile()) {
                            builder.addFormDataPart(key, file.getName(),
                                    RequestBody.create(file, MediaType.parse("application/octet-stream")));
                            partCount++;
                        }
                    }
                } else {
                    builder.addFormDataPart(key, value != null ? value : "");
                    partCount++;
                }
            }
        }
        // 空 form-data: MultipartBody 要求至少一个 part, 无 part 时返回空 body (等价于空提交)
        if (partCount == 0) {
            return RequestBody.create("", null);
        }
        return builder.build();
    }

    /**
     * 构建 application/x-www-form-urlencoded 请求体。
     * <p>
     * bodyContent 为 JSON 数组: {@code [{"key":"k1","value":"v1"}]}
     */
    private RequestBody buildFormBody(String bodyContent) throws IOException {
        FormBody.Builder builder = new FormBody.Builder();
        if (bodyContent != null && !bodyContent.trim().isEmpty()) {
            List<Map<String, String>> parts = objectMapper.readValue(bodyContent,
                    new TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> part : parts) {
                String key = part.get("key");
                if (key != null && !key.trim().isEmpty()) {
                    // 跳过 disabled 条目
                    String enabledStr = part.get("enabled");
                    if (enabledStr != null && !"true".equalsIgnoreCase(enabledStr)) {
                        continue;
                    }
                    String value = part.get("value");
                    builder.add(key, value != null ? value : "");
                }
            }
        }
        return builder.build();
    }

    /**
     * 构建原始文本请求体。
     * <p>
     * Content-Type 优先取 {@link RequestConfig#getRawContentType()};
     * 为空时从 headers 中查找 Content-Type; 仍为空时回退到 {@value #DEFAULT_CONTENT_TYPE}。
     */
    private RequestBody buildRawBody(RequestConfig config, String bodyContent) {
        String contentType = config.getRawContentType();
        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = resolveContentType(config);
        }
        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = DEFAULT_CONTENT_TYPE;
        }
        MediaType mediaType = MediaType.parse(contentType);
        String content = bodyContent != null ? bodyContent : "";
        return RequestBody.create(content, mediaType);
    }

    /**
     * 构建二进制文件请求体。
     * <p>
     * bodyContent 为文件路径, Content-Type 为 application/octet-stream。
     * 文件不存在时抛出 IllegalArgumentException。
     */
    private RequestBody buildBinaryBody(String bodyContent) throws IOException {
        if (bodyContent == null || bodyContent.trim().isEmpty()) {
            throw new IllegalArgumentException("二进制请求体需要指定文件路径");
        }
        File file = new File(bodyContent.trim());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + bodyContent);
        }
        return RequestBody.create(file, MediaType.parse("application/octet-stream"));
    }

    /**
     * 从 config headers 中解析 Content-Type。
     * <p>
     * 查找 enabled=true 且 key 为 Content-Type (不区分大小写) 的 header。
     * 未找到时返回默认值 {@value #DEFAULT_CONTENT_TYPE}。
     */
    private String resolveContentType(RequestConfig config) {
        List<KeyValue> headers = config.getHeaders();
        if (headers != null) {
            for (KeyValue header : headers) {
                if (header.isEnabled() && header.getKey() != null
                        && "content-type".equalsIgnoreCase(header.getKey().trim())) {
                    return header.getValue() != null ? header.getValue() : DEFAULT_CONTENT_TYPE;
                }
            }
        }
        return DEFAULT_CONTENT_TYPE;
    }

    /**
     * 设置 HTTP 方法与 body。
     * <p>
     * OkHttp 的 {@link Request.Builder#method(String, RequestBody)} 不允许 GET/HEAD 携带 body
     * (会抛 IllegalArgumentException)。作为 HTTP 测试工具, 需支持 GET 带 body, 这里通过反射
     * 直接写入 Request.Builder 内部的 method 与 body 字段绕过该校验。
     * 反射失败时回退到标准 method 调用 (GET/HEAD 无 body, 其余正常)。
     */
    private void applyMethod(Request.Builder requestBuilder, String method, RequestBody requestBody) {
        boolean bodyRestricted = "GET".equals(method) || "HEAD".equals(method);
        if (!bodyRestricted) {
            requestBuilder.method(method, requestBody);
            return;
        }
        // GET/HEAD: 若无 body, 走标准路径; 有 body 则反射写入
        if (requestBody == null) {
            requestBuilder.method(method, null);
            return;
        }
        try {
            Class<?> builderClass = Request.Builder.class;
            Field methodField = builderClass.getDeclaredField("method");
            methodField.setAccessible(true);
            methodField.set(requestBuilder, method);

            Field bodyField = builderClass.getDeclaredField("body");
            bodyField.setAccessible(true);
            bodyField.set(requestBuilder, requestBody);
        } catch (Exception e) {
            log.warn("GET/HEAD 反射设置 body 失败, 回退为无 body: {}", e.getMessage());
            requestBuilder.method(method, null);
        }
    }

    /**
     * 根据响应头 Content-Encoding 对响应字节做解压 (gzip / deflate)。
     * <p>
     * 由于默认发送 Accept-Encoding: gzip, deflate, 服务端可能返回压缩体, 而 OkHttp 在
     * 透明压缩被手动声明头时不会自动解压, 因此这里自行解压, 保证响应体可读 (修复服务器场景
     * 下 HTML 预览空白问题)。解压失败时返回原始字节, 不影响请求结果展示。
     */
    private byte[] decompressIfNeeded(byte[] bytes, String contentEncoding) {
        if (bytes == null || bytes.length == 0 || contentEncoding == null) {
            return bytes;
        }
        String encoding = contentEncoding.trim().toLowerCase();
        try {
            if ("gzip".equals(encoding)) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = gis.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } else if ("deflate".equals(encoding)) {
                // deflate 可能是 zlib 或 raw deflate, 用 Inflater(wrap=false) 兼容两者
                try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(bytes),
                        new Inflater(true));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = iis.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            }
        } catch (IOException e) {
            log.warn("响应体解压失败 ({}), 返回原始字节: {}", encoding, e.getMessage());
        }
        return bytes;
    }

    /**
     * 从响应头 Map 中按名称 (忽略大小写) 取值。
     */
    private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 读取输入流, 限制最大 MAX_RESPONSE_BYTES 字节。
     * <p>
     * 超过限制时截断, 不抛异常。
     *
     * @param inputStream 响应体输入流
     * @return 读取的字节数组 (最多 10MB)
     * @throws IOException 读取失败时
     */
    private byte[] readBodyLimited(InputStream inputStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = inputStream.read(buffer)) != -1) {
            if (total + read > MAX_RESPONSE_BYTES) {
                int toWrite = (int) (MAX_RESPONSE_BYTES - total);
                if (toWrite > 0) {
                    baos.write(buffer, 0, toWrite);
                }
                break;
            }
            baos.write(buffer, 0, read);
            total += read;
        }
        return baos.toByteArray();
    }

    /**
     * 单次请求的性能指标采集容器, 由 per-call {@link EventListener} 回调写入,
     * {@link #execute(RequestConfig)} 在请求结束后读取并填充到 {@link ResponseData}。
     * <p>
     * 仅在同步单线程调用场景下使用, 无需同步。
     */
    private static final class Timing {
        /** DNS 解析开始时间戳 (纳秒) */
        long dnsStart;
        /** TCP 连接开始时间戳 (纳秒) */
        long connectStart;
        /** 响应头开始到达时间戳 (纳秒) */
        long firstByteTime;
        /** DNS 解析耗时 (毫秒) */
        long dnsTime;
        /** TCP 连接耗时 (毫秒) */
        long tcpConnectTime;
        /** 首字节时间 TTFB (毫秒) */
        long ttfb;
    }
}
