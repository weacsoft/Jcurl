package com.jcurl.service;

import com.jcurl.http.TimingEventListener;
import com.jcurl.plugin.model.component.AuthConfig;
import com.jcurl.plugin.model.component.FormItem;
import com.jcurl.plugin.model.component.Header;
import com.jcurl.plugin.model.component.QueryParam;
import com.jcurl.plugin.model.dto.RequestConfig;
import com.jcurl.plugin.model.dto.ResponseData;
import com.jcurl.plugin.model.dto.TimingMetrics;
import com.jcurl.plugin.PluginService;
import com.jcurl.plugin.extension.RequestExecutorExtension;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.FormBody;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * HTTP 请求引擎 — 封装 OkHttp,负责请求构建、发送、响应解析、性能指标采集。
 * <p>
 * 核心能力:
 * <ul>
 *   <li>7 种标准方法:GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS</li>
 *   <li>5 种 Body 类型:none/form-data/x-www-form-urlencoded/raw/binary</li>
 *   <li>4 种认证:none/basic/bearer/apikey</li>
 *   <li>SSL 信任所有证书(本地调试自签名场景)</li>
 *   <li>EventListener 采集 DNS/TCP/TLS/TTFB/Total 耗时</li>
 *   <li>请求取消(通过 executionId 管理 Call 引用)</li>
 * </ul>
 */
@Service
public class HttpEngineService {

    private static final Logger log = LoggerFactory.getLogger(HttpEngineService.class);

    /** MediaType 映射:rawType → Content-Type */
    private static final Map<String, String> RAW_MEDIA_TYPES = Map.of(
            "json", "application/json; charset=utf-8",
            "xml", "application/xml; charset=utf-8",
            "html", "text/html; charset=utf-8",
            "text", "text/plain; charset=utf-8"
    );

    private OkHttpClient client;

    /** 进行中的请求 Call 映射,key=executionId,供取消使用 */
    private final Map<String, Call> activeCalls = new ConcurrentHashMap<>();

    /** 插件服务(@Lazy 避免循环依赖) */
    @Autowired
    @Lazy
    private PluginService pluginService;

    /** 默认请求头提供者 (自动头合并) */
    @Autowired
    private DefaultHeaderProvider defaultHeaderProvider;

    /** Cookie 管理服务 (按集合隔离的自动 Cookie) */
    @Autowired
    private CookieService cookieService;

    @PostConstruct
    public void init() {
        client = buildHttpClient();
    }

    /**
     * 同步执行请求。
     * <p>
     * 执行流程:
     * <ol>
     *   <li>调用插件请求拦截器({@link PluginService#beforeRequest})</li>
     *   <li>检查是否有自定义请求执行器插件,有则使用,否则使用 OkHttp</li>
     *   <li>调用插件响应拦截器({@link PluginService#afterResponse})</li>
     * </ol>
     *
     * @param config      请求配置(已解析变量、已合并集合继承)
     * @param executionId 执行 ID(用于取消请求,传 null 则不可取消)
     * @return 响应数据
     */
    public ResponseData execute(RequestConfig config, String executionId) {
        // 插件:请求拦截器
        if (pluginService != null) {
            config = pluginService.beforeRequest(config);
        }

        // 插件:检查自定义执行器
        if (pluginService != null) {
            RequestExecutorExtension customExecutor = pluginService.findRequestExecutor(config);
            if (customExecutor != null) {
                ResponseData response = customExecutor.execute(config, pluginService.getPluginContext());
                // 自动存储 Cookie(按当前集合上下文)
                cookieService.storeFromResponse(config.getUrl(), response.getHeaders());
                return pluginService.afterResponse(response, config);
            }
        }

        TimingEventListener listener = new TimingEventListener();
        Request request = buildRequest(config);

        Call call = client.newBuilder()
                .eventListener(listener)
                .build()
                .newCall(request);

        if (executionId != null) {
            activeCalls.put(executionId, call);
        }

        try {
            Response response = call.execute();
            ResponseData responseData = parseResponse(response, listener.getMetrics());
            // 自动存储 Cookie(按当前集合上下文)
            cookieService.storeFromResponse(config.getUrl(), responseData.getHeaders());
            // 插件:响应拦截器
            if (pluginService != null) {
                responseData = pluginService.afterResponse(responseData, config);
            }
            return responseData;
        } catch (IOException e) {
            ResponseData errorResponse = buildErrorResponse(e, listener.getMetrics(), call.isCanceled());
            // 插件:响应拦截器(即使是错误响应也通知)
            if (pluginService != null) {
                errorResponse = pluginService.afterResponse(errorResponse, config);
            }
            return errorResponse;
        } finally {
            if (executionId != null) {
                activeCalls.remove(executionId);
            }
        }
    }

    /**
     * 异步执行请求。
     * <p>
     * 与 {@link #execute} 相同,包含插件拦截器调用。
     *
     * @param config      请求配置
     * @param executionId 执行 ID
     * @return CompletableFuture(完成后含 ResponseData)
     */
    public CompletableFuture<ResponseData> executeAsync(RequestConfig config, String executionId) {
        // 插件:请求拦截器(使用 final 变量供 lambda 引用)
        final RequestConfig finalConfig;
        if (pluginService != null) {
            finalConfig = pluginService.beforeRequest(config);
        } else {
            finalConfig = config;
        }

        // 插件:检查自定义执行器
        if (pluginService != null) {
            RequestExecutorExtension customExecutor = pluginService.findRequestExecutor(finalConfig);
            if (customExecutor != null) {
                try {
                    ResponseData response = customExecutor.execute(finalConfig, pluginService.getPluginContext());
                    // 自动存储 Cookie(按当前集合上下文)
                    cookieService.storeFromResponse(finalConfig.getUrl(), response.getHeaders());
                    ResponseData intercepted = pluginService.afterResponse(response, finalConfig);
                    return CompletableFuture.completedFuture(intercepted);
                } catch (Exception e) {
                    ResponseData errorResponse = new ResponseData();
                    errorResponse.setError(e.getMessage());
                    return CompletableFuture.completedFuture(
                            pluginService.afterResponse(errorResponse, finalConfig));
                }
            }
        }

        CompletableFuture<ResponseData> future = new CompletableFuture<>();
        TimingEventListener listener = new TimingEventListener();
        Request request = buildRequest(finalConfig);

        Call call = client.newBuilder()
                .eventListener(listener)
                .build()
                .newCall(request);

        if (executionId != null) {
            activeCalls.put(executionId, call);
        }

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (executionId != null) activeCalls.remove(executionId);
                ResponseData errorResponse = buildErrorResponse(e, listener.getMetrics(), call.isCanceled());
                if (pluginService != null) {
                    errorResponse = pluginService.afterResponse(errorResponse, finalConfig);
                }
                future.complete(errorResponse);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (executionId != null) activeCalls.remove(executionId);
                try {
                    ResponseData responseData = parseResponse(response, listener.getMetrics());
                    // 自动存储 Cookie(按当前集合上下文)
                    cookieService.storeFromResponse(finalConfig.getUrl(), responseData.getHeaders());
                    if (pluginService != null) {
                        responseData = pluginService.afterResponse(responseData, finalConfig);
                    }
                    future.complete(responseData);
                } catch (Exception e) {
                    ResponseData errorResponse = buildErrorResponse(e, listener.getMetrics(), false);
                    if (pluginService != null) {
                        errorResponse = pluginService.afterResponse(errorResponse, finalConfig);
                    }
                    future.complete(errorResponse);
                }
            }
        });

        return future;
    }

    /** 取消指定执行 ID 的请求 */
    public boolean cancel(String executionId) {
        Call call = activeCalls.get(executionId);
        log.debug("cancel: id={}, call present={}, isCanceled={}", executionId, call != null, call != null && call.isCanceled());
        if (call != null && !call.isCanceled()) {
            call.cancel();
            return true;
        }
        return false;
    }

    /** 检查指定执行 ID 的请求是否仍在进行中(测试用) */
    public boolean isActive(String executionId) {
        return activeCalls.containsKey(executionId);
    }

    // ==================== 请求构建 ====================

    /**
     * 构建 OkHttp Request — 处理 URL+Params、Headers、Body、Auth。
     */
    private Request buildRequest(RequestConfig config) {
        // 1. URL + Query Params
        HttpUrl httpUrl = buildUrl(config.getUrl(), config.getParams());

        // 2. Request.Builder
        Request.Builder builder = new Request.Builder().url(httpUrl);

        // 3. Auth 注入(可能添加 Header 或 Query 参数)
        applyAuth(builder, httpUrl, config.getAuth());

        // 4. Headers —— 默认头与用户头合并(用户同名头覆盖默认头)
        String bodyType = config.getBody() != null ? config.getBody().getType() : null;
        String rawCt = defaultHeaderProvider.resolveRawContentType(config.getBody());
        List<Header> autoHeaders = defaultHeaderProvider.computeAutoHeaders(bodyType, rawCt);
        List<Header> merged = defaultHeaderProvider.mergeEffective(autoHeaders, config.getHeaders());
        // form-data / x-www-form-urlencoded 的 Content-Type 由 OkHttp 自动带 boundary, 跳过避免冲突
        boolean skipContentType = "form-data".equals(bodyType) || "x-www-form-urlencoded".equals(bodyType);
        for (Header header : merged) {
            if (header.getKey() == null || header.getKey().isBlank()) {
                continue;
            }
            if (skipContentType && "content-type".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }

        // 5. Cookie 自动管理(用户未手动设置 Cookie 且未禁用 Cookie 时,附带当前集合匹配的 cookie)
        if (config.isIncludeCookies()) {
            boolean userHasCookie = config.getHeaders().stream()
                    .anyMatch(h -> h.isEnabled() && h.getKey() != null
                            && "cookie".equalsIgnoreCase(h.getKey()));
            if (!userHasCookie) {
                String cookieValue = cookieService.getCookiesForUrl(config.getUrl());
                if (cookieValue != null && !cookieValue.isEmpty()) {
                    builder.header("Cookie", cookieValue);
                    log.debug("自动附加 Cookie: {}", cookieValue);
                }
            }
        }

        // 6. Body + Method
        String method = config.getMethod().toUpperCase();
        RequestBody body = buildBody(config.getBody(), method);
        builder.method(method, body);

        return builder.build();
    }

    /** 构建 URL,拼接启用的 Query Params */
    private HttpUrl buildUrl(String url, java.util.List<QueryParam> params) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            // URL 不合法时尝试补全 http://
            parsed = HttpUrl.parse("http://" + url);
        }
        if (parsed == null) {
            throw new IllegalArgumentException("无效的 URL: " + url);
        }

        HttpUrl.Builder urlBuilder = parsed.newBuilder();
        for (QueryParam param : params) {
            if (param.isEnabled() && param.getKey() != null && !param.getKey().isBlank()) {
                urlBuilder.addQueryParameter(param.getKey(), param.getValue());
            }
        }
        return urlBuilder.build();
    }

    /** 构建请求体 */
    private RequestBody buildBody(com.jcurl.plugin.model.component.RequestBody bodyConfig, String method) {
        // GET / HEAD 不能有 body
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return null;
        }

        String type = bodyConfig.getType();
        if (type == null || "none".equals(type)) {
            return RequestBody.create("", null);
        }

        switch (type) {
            case "form-data":
                return buildMultipartBody(bodyConfig);
            case "x-www-form-urlencoded":
                return buildFormBody(bodyConfig);
            case "raw":
                return buildRawBody(bodyConfig);
            case "binary":
                return buildBinaryBody(bodyConfig);
            default:
                return RequestBody.create("", null);
        }
    }

    /** form-data: MultipartBody(键值对 + 文件) */
    private RequestBody buildMultipartBody(com.jcurl.plugin.model.component.RequestBody bodyConfig) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (FormItem item : bodyConfig.getFormItems()) {
            if (!item.isEnabled() || item.getKey() == null || item.getKey().isBlank()) {
                continue;
            }
            if ("file".equals(item.getType())) {
                // 优先使用 Base64 文件内容,其次使用文件路径
                if (item.getFileContent() != null && !item.getFileContent().isEmpty()) {
                    byte[] bytes = Base64.getDecoder().decode(item.getFileContent());
                    String name = item.getFileName() != null ? item.getFileName() : item.getKey();
                    builder.addFormDataPart(item.getKey(), name,
                            RequestBody.create(bytes, MediaType.parse("application/octet-stream")));
                } else if (item.getFilePath() != null) {
                    File file = new File(item.getFilePath());
                    if (file.exists()) {
                        builder.addFormDataPart(item.getKey(), file.getName(),
                                RequestBody.create(file, MediaType.parse("application/octet-stream")));
                    }
                }
            } else {
                builder.addFormDataPart(item.getKey(), item.getValue() != null ? item.getValue() : "");
            }
        }
        return builder.build();
    }

    /** x-www-form-urlencoded: FormBody */
    private RequestBody buildFormBody(com.jcurl.plugin.model.component.RequestBody bodyConfig) {
        FormBody.Builder builder = new FormBody.Builder();
        for (FormItem item : bodyConfig.getFormItems()) {
            if (item.isEnabled() && item.getKey() != null && !item.getKey().isBlank()) {
                builder.add(item.getKey(), item.getValue() != null ? item.getValue() : "");
            }
        }
        return builder.build();
    }

    /** raw: 纯文本,按 rawType 设置 Content-Type */
    private RequestBody buildRawBody(com.jcurl.plugin.model.component.RequestBody bodyConfig) {
        String rawType = bodyConfig.getRawType() != null ? bodyConfig.getRawType() : "text";
        String mediaType = RAW_MEDIA_TYPES.getOrDefault(rawType, "text/plain; charset=utf-8");
        String content = bodyConfig.getContent() != null ? bodyConfig.getContent() : "";
        return RequestBody.create(content, MediaType.parse(mediaType));
    }

    /** binary: 单文件二进制流 */
    private RequestBody buildBinaryBody(com.jcurl.plugin.model.component.RequestBody bodyConfig) {
        // 优先使用 Base64 文件内容
        if (bodyConfig.getFileContent() != null && !bodyConfig.getFileContent().isEmpty()) {
            byte[] bytes = Base64.getDecoder().decode(bodyConfig.getFileContent());
            return RequestBody.create(bytes, MediaType.parse("application/octet-stream"));
        }
        if (bodyConfig.getFilePath() == null) {
            return RequestBody.create("", null);
        }
        File file = new File(bodyConfig.getFilePath());
        if (!file.exists()) {
            return RequestBody.create("", null);
        }
        return RequestBody.create(file, MediaType.parse("application/octet-stream"));
    }

    /** 认证注入 */
    private void applyAuth(Request.Builder builder, HttpUrl httpUrl, AuthConfig auth) {
        if (auth == null || auth.getType() == null) {
            return;
        }

        switch (auth.getType()) {
            case "basic":
                String credential = auth.getUsername() + ":" + (auth.getPassword() != null ? auth.getPassword() : "");
                String encoded = Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
                break;
            case "bearer":
                if (auth.getToken() != null && !auth.getToken().isBlank()) {
                    builder.header("Authorization", "Bearer " + auth.getToken());
                }
                break;
            case "apikey":
                if (auth.getKey() != null && !auth.getKey().isBlank()) {
                    if ("query".equals(auth.getAddTo())) {
                        HttpUrl newUrl = httpUrl.newBuilder()
                                .addQueryParameter(auth.getKey(), auth.getValue() != null ? auth.getValue() : "")
                                .build();
                        builder.url(newUrl);
                    } else {
                        builder.header(auth.getKey(), auth.getValue() != null ? auth.getValue() : "");
                    }
                }
                break;
            case "none":
            case "inherit":
            default:
                // 无操作
                break;
        }
    }

    // ==================== 响应解析 ====================

    /** 解析 OkHttp Response 为 ResponseData */
    private ResponseData parseResponse(Response response, TimingMetrics metrics) {
        ResponseData data = new ResponseData();
        data.setTiming(metrics);

        try {
            data.setStatusCode(response.code());
            data.setStatusText(response.message());
            data.setProtocol(response.protocol().toString());

            // 响应头
            Headers headers = response.headers();
            for (int i = 0; i < headers.size(); i++) {
                data.getHeaders().add(new Header(headers.name(i), headers.value(i)));
            }

            // Content-Type
            String contentType = response.header("Content-Type");
            data.setContentType(contentType);

            // 响应体
            if (response.body() != null) {
                byte[] bytes = response.body().bytes();
                // 手动解压 gzip/deflate: 默认头发送了 Accept-Encoding, OkHttp 不再自动解压
                bytes = decompressIfNeeded(bytes, response.header("Content-Encoding"));
                data.setSize(bytes.length);
                // 判断是否为文本内容(非二进制)
                if (isTextContent(contentType)) {
                    data.setBody(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    // 二进制内容(图片等): 存 Base64 供 UI 渲染图片预览
                    data.setBody("[Binary Content]");
                    data.setBinaryBase64(Base64.getEncoder().encodeToString(bytes));
                }
            }
        } catch (IOException e) {
            data.setError("读取响应体失败: " + e.getMessage());
            log.error("读取响应体失败", e);
        } finally {
            response.close();
        }

        return data;
    }

    /** 判断 Content-Type 是否为文本内容 */
    private boolean isTextContent(String contentType) {
        if (contentType == null) {
            return true; // 默认按文本处理
        }
        String lower = contentType.toLowerCase();
        return lower.contains("text") || lower.contains("json") || lower.contains("xml")
                || lower.contains("html") || lower.contains("javascript") || lower.contains("form-data")
                || lower.contains("x-www-form-urlencoded");
    }

    /**
     * 按响应头 Content-Encoding 解压响应体。
     * 由于默认请求头带上了 Accept-Encoding: gzip, deflate, OkHttp 不再自动解压, 需手动处理。
     */
    private byte[] decompressIfNeeded(byte[] bytes, String contentEncoding) {
        if (bytes == null || bytes.length == 0 || contentEncoding == null || contentEncoding.isBlank()) {
            return bytes;
        }
        String enc = contentEncoding.trim().toLowerCase();
        try {
            if (enc.contains("gzip")) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    return gis.readAllBytes();
                }
            } else if (enc.contains("deflate")) {
                try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
                    return iis.readAllBytes();
                }
            }
        } catch (IOException e) {
            log.warn("解压响应体失败 ({}): {}", enc, e.getMessage());
        }
        return bytes;
    }

    /** 构建错误响应 */
    private ResponseData buildErrorResponse(Exception e, TimingMetrics metrics, boolean cancelled) {
        ResponseData data = new ResponseData();
        data.setTiming(metrics);
        data.setCancelled(cancelled);
        if (cancelled) {
            data.setError("请求已取消");
        } else {
            data.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        log.debug("请求失败: {}", data.getError());
        return data;
    }

    // ==================== HttpClient 构建 ====================

    /** 构建 OkHttpClient(信任所有 SSL 证书,支持本地调试) */
    private OkHttpClient buildHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);

        // 信任所有 SSL 证书(本地调试自签名场景)
        try {
            X509TrustManager trustAllManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());

            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAllManager);
            builder.hostnameVerifier((String hostname, SSLSession session) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.warn("SSL 信任配置失败,回退默认验证", e);
        }

        return builder.build();
    }
}
