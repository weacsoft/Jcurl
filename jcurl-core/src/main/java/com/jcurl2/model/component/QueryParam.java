package com.jcurl2.model.component;

/**
 * URL 查询参数键值对。
 */
public class QueryParam {
    private String key;
    private String value;
    private boolean enabled = true;
    private String description;

    public QueryParam() {}

    public QueryParam(String key, String value) {
        this.key = key;
        this.value = value;
        this.enabled = true;
    }

    public QueryParam(String key, String value, boolean enabled) {
        this.key = key;
        this.value = value;
        this.enabled = enabled;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
