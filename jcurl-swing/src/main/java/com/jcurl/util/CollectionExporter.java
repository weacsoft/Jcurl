package com.jcurl.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.CollectionFile;

/**
 * 集合导出器 — 导出为自定义 JSON 格式。
 */
public class CollectionExporter {

    /**
     * 导出集合为 JSON 字符串。
     */
    public static String exportToJson(CollectionFile collection, ObjectMapper objectMapper) throws Exception {
        return objectMapper.writeValueAsString(collection);
    }
}
