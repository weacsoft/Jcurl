package com.jcurl2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jcurl2.model.component.AuthConfig;
import com.jcurl2.model.component.Header;
import com.jcurl2.model.component.QueryParam;
import com.jcurl2.model.component.RequestBody;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求节点 — 集合树中的实际 API 请求,含完整的请求配置。
 * <p>
 * auth.type="inherit" 时继承集合级认证配置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequestNode extends CollectionItem {

    /** HTTP 方法: GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS */
    private String method = "GET";

    /** 请求 URL(可为相对路径,拼接集合级 baseUrl) */
    private String url = "";

    /** 查询参数 */
    private List<QueryParam> params = new ArrayList<>();

    /** 请求头 */
    private List<Header> headers = new ArrayList<>();

    /** 请求体 */
    private RequestBody body = new RequestBody("none");

    /** 认证配置(type=inherit 时继承集合级) */
    private AuthConfig auth = AuthConfig.inherit();

    /** 前置脚本(预留,本版不实现脚本执行) */
    private String preScript;

    /** 后置脚本(预留) */
    private String postScript;

    public RequestNode() {
        // Jackson 反序列化需要
    }

    public RequestNode(String id, String name, String method, String url) {
        this.id = id;
        this.name = name;
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
        if (auth == null) auth = AuthConfig.inherit();
        return auth;
    }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    public String getPreScript() { return preScript; }
    public void setPreScript(String preScript) { this.preScript = preScript; }
    public String getPostScript() { return postScript; }
    public void setPostScript(String postScript) { this.postScript = postScript; }

    @Override
    public boolean isRequest() {
        return true;
    }
}
