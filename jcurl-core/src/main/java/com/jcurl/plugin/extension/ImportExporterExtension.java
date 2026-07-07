package com.jcurl.plugin.extension;

import com.jcurl.model.Collection;
import com.jcurl.model.RequestNode;
import com.jcurl.plugin.ExtensionPoint;
import com.jcurl.plugin.PluginContext;

import java.util.List;

/**
 * 导入导出扩展点 — 支持自定义格式的导入和导出。
 * <p>
 * 内置格式:Postman v2.1、OpenAPI 3.0、cURL。
 * 插件可通过此扩展点添加更多格式,如 HAR、Swagger 2.0、RAML、Insomnia 等。
 */
public interface ImportExporterExtension extends ExtensionPoint {

    /**
     * 获取此扩展支持的导入格式列表(文件扩展名,不含点)。
     * <p>
     * 如 ["har", "swagger"]。
     *
     * @return 格式列表
     */
    List<String> getSupportedImportFormats();

    /**
     * 获取此扩展支持的导出格式列表。
     */
    List<String> getSupportedExportFormats();

    /**
     * 导入指定格式的内容为 Collection。
     *
     * @param format       格式名(由 getSupportedImportFormats 返回)
     * @param content      文件内容字符串
     * @param ctx          插件上下文
     * @return 导入的 Collection 列表
     */
    List<Collection> importContent(String format, String content, PluginContext ctx) throws Exception;

    /**
     * 导出 Collection 为指定格式的字符串。
     *
     * @param format     格式名
     * @param collection 要导出的集合
     * @param ctx        插件上下文
     * @return 导出的字符串内容
     */
    String exportCollection(String format, Collection collection, PluginContext ctx) throws Exception;
}
