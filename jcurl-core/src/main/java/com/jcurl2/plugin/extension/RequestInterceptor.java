package com.jcurl2.plugin.extension;

import com.jcurl2.model.dto.RequestConfig;
import com.jcurl2.plugin.ExtensionPoint;
import com.jcurl2.plugin.PluginContext;

/**
 * 请求拦截器扩展点 — 在 HTTP 请求发送前拦截,可修改请求配置。
 * <p>
 * 典型场景:自动添加签名 Header、请求日志记录、请求参数加密、请求重定向等。
 * <p>
 * 执行顺序:按插件加载顺序依次执行,前一个拦截器的输出作为后一个的输入(责任链模式)。
 */
public interface RequestInterceptor extends ExtensionPoint {

    /**
     * 请求发送前回调。
     *
     * @param config 当前请求配置(可修改)
     * @param ctx    插件上下文
     * @return 修改后的请求配置(通常直接修改并返回 config)
     */
    RequestConfig beforeRequest(RequestConfig config, PluginContext ctx);
}
