package com.jcurl.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 历史记录 — 一次请求执行的完整快照。
 * <p>
 * 存储在 history.json 文件中, 默认保留最近 500 条。
 */
public class HistoryRecord {

    private String id;
    private LocalDateTime timestamp;

    // 请求信息
    private String requestName;
    private String method;
    private String url;
    private List<KeyValue> requestHeaders = new ArrayList<>();
    private String requestBody;
    private String bodyType;

    // 响应信息
    private int statusCode;
    private String statusText;
    private List<KeyValue> responseHeaders = new ArrayList<>();
    private String responseBody;
    private long responseTime;
    private long responseSize;

    public HistoryRecord() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getRequestName() {
        return requestName;
    }

    public void setRequestName(String requestName) {
        this.requestName = requestName;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<KeyValue> getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(List<KeyValue> requestHeaders) {
        this.requestHeaders = requestHeaders != null ? requestHeaders : new ArrayList<>();
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getBodyType() {
        return bodyType;
    }

    public void setBodyType(String bodyType) {
        this.bodyType = bodyType;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    public List<KeyValue> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(List<KeyValue> responseHeaders) {
        this.responseHeaders = responseHeaders != null ? responseHeaders : new ArrayList<>();
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public long getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(long responseTime) {
        this.responseTime = responseTime;
    }

    public long getResponseSize() {
        return responseSize;
    }

    public void setResponseSize(long responseSize) {
        this.responseSize = responseSize;
    }
}
