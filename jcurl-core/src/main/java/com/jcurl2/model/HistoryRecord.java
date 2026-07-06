package com.jcurl2.model;

import com.jcurl2.model.component.AuthConfig;
import com.jcurl2.model.component.Header;
import com.jcurl2.model.component.QueryParam;
import com.jcurl2.model.component.RequestBody;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史记录 — 每次发送请求后自动保存的完整请求与响应信息。
 * <p>
 * 存储在 history.json 中,默认上限 500 条,超限自动删除最旧记录。
 */
public class HistoryRecord {

    private String id;
    private String name;

    // === 请求信息 ===
    private String method;
    private String url;
    private List<QueryParam> params = new ArrayList<>();
    private List<Header> headers = new ArrayList<>();
    private RequestBody body;
    private AuthConfig auth;

    // === 响应信息 ===
    private int statusCode;
    private String statusText;
    private List<Header> responseHeaders = new ArrayList<>();
    /** 响应体(超过 1MB 时截断) */
    private String responseBody;
    private String responseContentType;
    private long responseSize;
    /** 总耗时(ms) */
    private long responseTime;
    /** 首字节时间(ms) */
    private long ttfb;

    // === 元信息 ===
    private LocalDateTime timestamp;

    public HistoryRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<QueryParam> getParams() {
        if (params == null) params = new ArrayList<>();
        return params;
    }
    public void setParams(List<QueryParam> params) { this.params = params; }
    public List<Header> getHeaders() {
        if (headers == null) headers = new ArrayList<>();
        return headers;
    }
    public void setHeaders(List<Header> headers) { this.headers = headers; }
    public RequestBody getBody() { return body; }
    public void setBody(RequestBody body) { this.body = body; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getStatusText() { return statusText; }
    public void setStatusText(String statusText) { this.statusText = statusText; }
    public List<Header> getResponseHeaders() {
        if (responseHeaders == null) responseHeaders = new ArrayList<>();
        return responseHeaders;
    }
    public void setResponseHeaders(List<Header> responseHeaders) { this.responseHeaders = responseHeaders; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public String getResponseContentType() { return responseContentType; }
    public void setResponseContentType(String responseContentType) { this.responseContentType = responseContentType; }
    public long getResponseSize() { return responseSize; }
    public void setResponseSize(long responseSize) { this.responseSize = responseSize; }
    public long getResponseTime() { return responseTime; }
    public void setResponseTime(long responseTime) { this.responseTime = responseTime; }
    public long getTtfb() { return ttfb; }
    public void setTtfb(long ttfb) { this.ttfb = ttfb; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    /**
     * 生成摘要字符串,如 "[GET] /api/users - 200 OK - 2.3ms - 2026-07-03 14:30"。
     */
    public String toSummary() {
        return String.format("[%s] %s - %d %s - %.1fms - %s",
                method, url, statusCode,
                statusText != null ? statusText : "",
                (double) responseTime,
                timestamp != null ? timestamp.toString() : "");
    }
}
