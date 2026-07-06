package com.jpostman.model.dto;

import com.jpostman.model.AuthConfig;
import com.jpostman.model.KeyValue;
import com.jpostman.model.RequestNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求配置 DTO — 一次请求的完整配置数据。
 * <p>
 * 可以是某个 RequestNode 的快照(通过 {@link #from(RequestNode)} 创建),
 * 也可以是用户临时构建未保存的。它是 HttpEngine 的输入。
 */
public class RequestConfig {

    private String method = "GET";
    private String url;
    private List<KeyValue> params = new ArrayList<>();
    private List<KeyValue> headers = new ArrayList<>();
    private String bodyType = "none";
    private String bodyContent;
    private String rawContentType;
    private AuthConfig auth = new AuthConfig();
    /** 是否自动跟随重定向 (301/302/303/307/308), 默认 true */
    private boolean followRedirects = true;

    public RequestConfig() {
    }

    /**
     * 从 RequestNode 创建请求配置 DTO。
     *
     * @param node 请求节点, 不能为 null
     * @return 请求配置 DTO
     */
    public static RequestConfig from(RequestNode node) {
        if (node == null) {
            throw new IllegalArgumentException("RequestNode 不能为 null");
        }
        RequestConfig config = new RequestConfig();
        config.method = node.getMethod();
        config.url = node.getUrl();
        config.bodyType = node.getBodyType();
        config.bodyContent = node.getBodyContent();
        config.rawContentType = node.getRawContentType();
        config.auth = node.getAuth();
        config.params = new ArrayList<>(node.getParams());
        config.headers = new ArrayList<>(node.getHeaders());
        return config;
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

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }
}
