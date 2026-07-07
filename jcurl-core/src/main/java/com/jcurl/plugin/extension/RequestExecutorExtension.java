package com.jcurl.plugin.extension;

import com.jcurl.plugin.model.dto.RequestConfig;
import com.jcurl.plugin.model.dto.ResponseData;
import com.jcurl.plugin.ExtensionPoint;
import com.jcurl.plugin.PluginContext;

/**
 * 请求执行器扩展点 — 替代默认的 OkHttp 执行引擎,实现自定义请求执行。
 * <p>
 * 典型场景:通过代理服务器发送请求、使用 WebSocket 协议、gRPC 请求、
 * 集成第三方 HTTP 库(如 Apache HttpClient)等。
 * <p>
 * 注意:一次请求只会使用一个 RequestExecutorExtension(优先级最高的那个)。
 * 如果没有注册任何执行器扩展,则使用内置的 OkHttp 引擎。
 */
public interface RequestExecutorExtension extends ExtensionPoint {

    /**
     * 获取执行器优先级(数字越大优先级越高)。
     * <p>
     * 当多个执行器同时注册时,优先级最高的被使用。
     *
     * @return 优先级
     */
    int getPriority();

    /**
     * 判断此执行器是否支持处理该请求。
     *
     * @param config 请求配置
     * @param ctx    插件上下文
     * @return true 表示此执行器可以处理该请求
     */
    boolean supports(RequestConfig config, PluginContext ctx);

    /**
     * 执行请求。
     *
     * @param config 请求配置
     * @param ctx    插件上下文
     * @return 响应数据
     */
    ResponseData execute(RequestConfig config, PluginContext ctx);
}
