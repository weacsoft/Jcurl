package com.jpostman.plugin;

import com.jpostman.model.dto.RequestConfig;

/**
 * 请求拦截器扩展点 — 在请求发送前修改请求。
 */
public interface RequestInterceptor {
    /**
     * 处理请求, 可修改并返回新的 RequestConfig。
     * @param config 原始请求配置
     * @return 修改后的请求配置
     */
    RequestConfig intercept(RequestConfig config);
}
