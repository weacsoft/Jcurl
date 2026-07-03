package com.jpostman.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求节点 — 集合树中的实际 API 请求定义。
 * <p>
 * 包含完整的请求配置: method, url, params, headers, body, auth。
 * 可继承所属 Collection 的 baseUrl、公共 headers 和公共 auth。
 */
public class RequestNode extends CollectionItem {

    private String method = "GET";
    private String url;
    private String description;

    private List<KeyValue> params = new ArrayList<>();
    private List<KeyValue> headers = new ArrayList<>();

    /** Body 类型: none / form-data / urlencoded / raw / binary */
    private String bodyType = "none";
    /** Body 内容: raw 模式为文本, form-data/urlencoded 为 JSON 数组, binary 为文件路径 */
    private String bodyContent;
    /** Raw body 的 Content-Type (如 application/json, text/plain 等) */
    private String rawContentType;

    private AuthConfig auth = new AuthConfig();

    public RequestNode() {
        super();
    }

    public RequestNode(String name) {
        super(name);
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<KeyValue> getParams() {
        return params;
    }

    public void setParams(List<KeyValue> params) {
        this.params = params != null ? params : new ArrayList<>();
    }

    public List<KeyValue> getHeaders() {
        return headers;
    }

    public void setHeaders(List<KeyValue> headers) {
        this.headers = headers != null ? headers : new ArrayList<>();
    }

    public String getBodyType() {
        return bodyType;
    }

    public void setBodyType(String bodyType) {
        this.bodyType = bodyType;
    }

    public String getBodyContent() {
        return bodyContent;
    }

    public void setBodyContent(String bodyContent) {
        this.bodyContent = bodyContent;
    }

    public String getRawContentType() {
        return rawContentType;
    }

    public void setRawContentType(String rawContentType) {
        this.rawContentType = rawContentType;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth != null ? auth : new AuthConfig();
    }
}
