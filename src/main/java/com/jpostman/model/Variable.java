package com.jpostman.model;

/**
 * 变量 — 用于环境变量、集合变量、全局变量。
 * <p>
 * 与 KeyValue 的区别: 增加 secret 标记, 加密变量的值在存储时进行 Base64 编码。
 */
public class Variable {

    private String key;
    private String value;
    private String description;
    private boolean enabled = true;
    /** 是否为加密变量 (Secret) */
    private boolean secret = false;

    public Variable() {
    }

    public Variable(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public Variable(String key, String value, boolean enabled) {
        this.key = key;
        this.value = value;
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

    public boolean isSecret() {
        return secret;
    }

    public void setSecret(boolean secret) {
        this.secret = secret;
    }
}
