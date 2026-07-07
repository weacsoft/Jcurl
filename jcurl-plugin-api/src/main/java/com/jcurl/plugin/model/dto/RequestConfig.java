package com.jcurl.plugin.model.dto;

import com.jcurl.plugin.model.component.AuthConfig;
import com.jcurl.plugin.model.component.Header;
import com.jcurl.plugin.model.component.QueryParam;
import com.jcurl.plugin.model.component.RequestBody;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求执行配置 — 经变量解析、集合继承合并后的最终请求配置,供 HttpEngineService 执行。
 * <p>
 * 与 {@link com.jcurl.model.RequestNode} 的区别:
 * RequestNode 是集合树中的持久化节点;RequestConfig 是执行时的瞬时配置,已合并集合级配置、已解析变量。
 */
public class RequestConfig {

    private String method = "GET";
    private String url = "";
    private List<QueryParam> params = new ArrayList<>();
    private List<Header> headers = new ArrayList<>();
    private RequestBody body = new RequestBody("none");
    private AuthConfig auth = AuthConfig.none();
    /** 是否自动附带 Cookie (从当前集合存储中匹配域名/路径), 默认 true */
    private boolean includeCookies = true;

    public RequestConfig() {}

    public RequestConfig(String method, String url) {
        this.method = method;
        this.url = url;
    }

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
    public RequestBody getBody() {
        if (body == null) body = new RequestBody("none");
        return body;
    }
    public void setBody(RequestBody body) { this.body = body; }
    public AuthConfig getAuth() {
        if (auth == null) auth = AuthConfig.none();
        return auth;
    }
    public void setAuth(AuthConfig auth) { this.auth = auth; }

    public boolean isIncludeCookies() { return includeCookies; }
    public void setIncludeCookies(boolean includeCookies) { this.includeCookies = includeCookies; }
}
