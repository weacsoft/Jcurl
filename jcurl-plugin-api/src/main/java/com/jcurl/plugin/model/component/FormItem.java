package com.jcurl.plugin.model.component;

/**
 * 表单项 — 用于 form-data 和 x-www-form-urlencoded。
 * <p>
 * form-data 模式下,type 可为 text 或 file。
 * file 类型时,fileContent 存储 Base64 编码的文件内容,fileName 存储文件名(不含路径)。
 * filePath 保留用于向后兼容,优先使用 fileContent。
 */
public class FormItem {
    private String key;
    private String value;
    /** text / file */
    private String type = "text";
    /** file 类型时的文件路径(向后兼容,优先使用 fileContent) */
    private String filePath;
    /** file 类型时的文件内容(Base64 编码) */
    private String fileContent;
    /** file 类型时的文件名(不含路径,用于显示) */
    private String fileName;
    private boolean enabled = true;
    private String description;

    public FormItem() {}

    public FormItem(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public static FormItem file(String key, String filePath) {
        FormItem item = new FormItem();
        item.key = key;
        item.type = "file";
        item.filePath = filePath;
        return item;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
