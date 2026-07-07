package com.jcurl2.plugin.extension;

import com.jcurl2.model.dto.RequestConfig;
import com.jcurl2.model.dto.ResponseData;
import com.jcurl2.plugin.ExtensionPoint;
import com.jcurl2.plugin.PluginContext;

/**
 * 响应拦截器扩展点 — 在 HTTP 响应接收后拦截,可修改响应数据。
 * <p>
 * 典型场景:响应解密、响应日志记录、自动提取 Token、响应体格式转换、断言校验等。
 * <p>
 * 执行顺序:按插件加载顺序依次执行,前一个拦截器的输出作为后一个的输入(责任链模式)。
 */
public interface ResponseInterceptor extends ExtensionPoint {

    /**
     * 响应接收后回调。
     *
     * @param response 当前响应数据(可修改)
     * @param config   原始请求配置(只读)
     * @param ctx      插件上下文
     * @return 修改后的响应数据(通常直接修改并返回 response)
     */
    ResponseData afterResponse(ResponseData response, RequestConfig config, PluginContext ctx);
}
