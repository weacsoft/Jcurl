package com.jcurl2.model.component;

/**
 * 变量键值对 — 用于环境变量、集合变量、全局变量。
 * <p>
 * secret=true 时,值在 UI 显示为 ******,存储时做 Base64 编码。
 */
public class Variable {
    private String key;
    private String value;
    private boolean secret = false;
    private String description;
    private boolean enabled = true;

    public Variable() {}

    public Variable(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public Variable(String key, String value, boolean secret) {
        this.key = key;
        this.value = value;
        this.secret = secret;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public boolean isSecret() { return secret; }
    public void setSecret(boolean secret) { this.secret = secret; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
