package com.jpostman.model.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * 响应数据 DTO — 一次请求执行返回的结果。
 * <p>
 * v2 增强: 新增性能指标 (DNS 解析时间、TCP 连接时间、首字节时间 TTFB)、
 * 协议版本、响应体原始字节数组 (用于图片预览)。
 * <p>
 * 网络错误时 statusCode 为 0, errorMessage 填充错误信息。
 */
public class ResponseData {

    /** HTTP 状态码, 网络错误时为 0 */
    private int statusCode;

    /** HTTP 状态文本 (如 "OK", "Not Found") */
    private String statusText;

    /** HTTP 协议版本 (如 "HTTP/1.1", "HTTP/2") */
    private String protocolVersion;

    /** 响应头, 键值对 */
    private Map<String, String> responseHeaders = new HashMap<>();

    /** 响应体, 字符串形式 */
    private String responseBody;

    /** 响应体原始字节 (用于图片预览等二进制内容) */
    private byte[] responseBytes;

    /** 响应总耗时 (毫秒) */
    private long responseTime;

    /** 响应大小 (字节) */
    private long responseSize;

    /** DNS 解析时间 (毫秒) */
    private long dnsTime;

    /** TCP 连接时间 (毫秒) */
    private long tcpConnectTime;

    /** 首字节时间 TTFB (毫秒) */
    private long ttfb;

    /** 网络错误信息, 正常响应时为 null */
    private String errorMessage;

    /** 请求 URL (含 query), 供响应面板推导 <base href> 以渲染 HTML 中的相对资源 */
    private String requestUrl;

    public ResponseData() {
    }

    /**
     * 判断请求是否成功 (2xx 状态码)。
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
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

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, String> responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public byte[] getResponseBytes() {
        return responseBytes;
    }

    public void setResponseBytes(byte[] responseBytes) {
        this.responseBytes = responseBytes;
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

    public long getDnsTime() {
        return dnsTime;
    }

    public void setDnsTime(long dnsTime) {
        this.dnsTime = dnsTime;
    }

    public long getTcpConnectTime() {
        return tcpConnectTime;
    }

    public void setTcpConnectTime(long tcpConnectTime) {
        this.tcpConnectTime = tcpConnectTime;
    }

    public long getTtfb() {
        return ttfb;
    }

    public void setTtfb(long ttfb) {
        this.ttfb = ttfb;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }
}
