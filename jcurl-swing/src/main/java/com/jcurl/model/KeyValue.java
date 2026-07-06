package com.jcurl.model;

/**
 * 键值对 — 用于 Params、Headers 等表格数据。
 * <p>
 * 字段: key, value, description, enabled。
 * enabled 为 false 时该键值对在请求发送时被忽略。
 */
public class KeyValue {

    private String key;
    private String value;
    private String description;
    private boolean enabled = true;

    public KeyValue() {
    }

    public KeyValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public KeyValue(String key, String value, String description, boolean enabled) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
