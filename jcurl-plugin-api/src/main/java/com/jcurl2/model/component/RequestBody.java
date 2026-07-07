package com.jcurl2.model.component;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求体配置。
 * <p>
 * type 取值:
 * <ul>
 *   <li>none — 无请求体</li>
 *   <li>form-data — 混合表单(键值对 + 文件上传)</li>
 *   <li>x-www-form-urlencoded — 标准 URL 编码表单</li>
 *   <li>raw — 纯文本(rawType: json/xml/html/text)</li>
 *   <li>binary — 单文件二进制流</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequestBody {

    private String type = "none";

    /** raw 模式下的文本内容 */
    private String content;

    /** raw 模式下的语言类型: json / xml / html / text */
    private String rawType = "json";

    /** form-data / urlencoded 模式下的表单项 */
    private List<FormItem> formItems;

    /** binary 模式下的文件路径 */
    private String filePath;

    /** binary 模式下的文件内容(Base64 编码) */
    private String fileContent;

    /** binary 模式下的文件名(不含路径,用于显示) */
    private String fileName;

    public RequestBody() {}

    public RequestBody(String type) {
        this.type = type;
    }

    public static RequestBody none() {
        return new RequestBody("none");
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getRawType() { return rawType; }
    public void setRawType(String rawType) { this.rawType = rawType; }
    public List<FormItem> getFormItems() {
        if (formItems == null) {
            formItems = new ArrayList<>();
        }
        return formItems;
    }
    public void setFormItems(List<FormItem> formItems) { this.formItems = formItems; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}
