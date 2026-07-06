package com.jcurl.plugin;

import com.jcurl.model.dto.ResponseData;

/**
 * 响应处理器扩展点 — 在收到响应后处理响应数据。
 */
public interface ResponseProcessor {
    ResponseData process(ResponseData response);
}
