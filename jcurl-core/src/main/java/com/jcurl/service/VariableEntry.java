package com.jcurl.service;

/**
 * 变量条目 — 用于变量自动补全展示。
 */
public class VariableEntry {

    private final String key;
    private final String displayValue;  // secret 变量显示 ******
    private final String scope;          // 作用域名: Local / Environment / Collection / Global
    private final boolean secret;

    public VariableEntry(String key, String displayValue, String scope, boolean secret) {
        this.key = key;
        this.displayValue = displayValue;
        this.scope = scope;
        this.secret = secret;
    }

    public String getKey() { return key; }
    public String getDisplayValue() { return displayValue; }
    public String getScope() { return scope; }
    public boolean isSecret() { return secret; }
}
