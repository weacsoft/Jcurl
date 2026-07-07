package com.jcurl2.model.dto;

import com.jcurl2.model.component.Header;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应数据 — HTTP 请求执行后的完整响应,供 UI 渲染与历史记录使用。
 */
public class ResponseData {

    /** HTTP 状态码,如 200 */
    private int statusCode;

    /** 状态文本,如 "OK" */
    private String statusText;

    /** 协议版本,如 "HTTP/1.1" / "HTTP/2" */
    private String protocol;

    /** 响应头 */
    private List<Header> headers = new ArrayList<>();

    /** 响应体(字符串形式;二进制响应如图片不在此字段,由 UI 单独处理) */
    private String body;

    /** Content-Type */
    private String contentType;

    /** 响应体大小(字节) */
    private long size;

    /** 性能指标 */
    private TimingMetrics timing;

    /** 是否请求被取消 */
    private boolean cancelled;

    /** 错误信息(请求失败时) */
    private String error;

    /** 二进制响应体(如图片)的 Base64 编码, 供 UI 渲染图片预览; 文本响应为 null */
    private String binaryBase64;

    public ResponseData() {}

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getStatusText() { return statusText; }
    public void setStatusText(String statusText) { this.statusText = statusText; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public List<Header> getHeaders() {
        if (headers == null) headers = new ArrayList<>();
        return headers;
    }
    public void setHeaders(List<Header> headers) { this.headers = headers; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public TimingMetrics getTiming() {
        if (timing == null) timing = new TimingMetrics();
        return timing;
    }
    public void setTiming(TimingMetrics timing) { this.timing = timing; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getBinaryBase64() { return binaryBase64; }
    public void setBinaryBase64(String binaryBase64) { this.binaryBase64 = binaryBase64; }

    public boolean isSuccess() {
        return error == null && !cancelled && statusCode > 0;
    }
}
