package com.jcurl.ui.event;

import com.jcurl.plugin.model.dto.ResponseData;

/**
 * 响应监听器 — 请求执行完成后通知响应展示区域。
 * <p>
 * 由响应展示视图(步骤 10)实现,请求构建器通过此接口通知响应到达。
 */
@FunctionalInterface
public interface ResponseListener {

    /**
     * 响应到达时调用。
     *
     * @param response 响应数据(含状态码、响应头、响应体、性能指标)
     */
    void onResponse(ResponseData response);
}
