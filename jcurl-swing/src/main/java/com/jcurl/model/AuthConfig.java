package com.jcurl.model;

/**
 * 认证配置 — 支持四种认证方式。
 * <p>
 * type 取值:
 * <ul>
 *   <li>"none" — 无认证</li>
 *   <li>"apikey" — API Key (key + value + in: header/query/cookie)</li>
 *   <li>"bearer" — Bearer Token (token)</li>
 *   <li>"basic" — Basic Auth (username + password)</li>
 * </ul>
 */
public class AuthConfig {

    private String type = "none";

    // API Key
    private String apiKeyName;
    private String apiKeyValue;
    /** API Key 存放位置: header / query / cookie */
    private String apiKeyIn = "header";

    // Bearer Token
    private String bearerToken;

    // Basic Auth
    private String basicUsername;
    private String basicPassword;

    public AuthConfig() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getApiKeyName() {
        return apiKeyName;
    }

    public void setApiKeyName(String apiKeyName) {
        this.apiKeyName = apiKeyName;
    }

    public String getApiKeyValue() {
        return apiKeyValue;
    }

    public void setApiKeyValue(String apiKeyValue) {
        this.apiKeyValue = apiKeyValue;
    }

    public String getApiKeyIn() {
        return apiKeyIn;
    }

    public void setApiKeyIn(String apiKeyIn) {
        this.apiKeyIn = apiKeyIn;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getBasicUsername() {
        return basicUsername;
    }

    public void setBasicUsername(String basicUsername) {
        this.basicUsername = basicUsername;
    }

    public String getBasicPassword() {
        return basicPassword;
    }

    public void setBasicPassword(String basicPassword) {
        this.basicPassword = basicPassword;
    }

    /**
     * 判断认证是否为空 (type=none 或 type 为空)。
     */
    public boolean isEmpty() {
        return type == null || type.trim().isEmpty() || "none".equalsIgnoreCase(type.trim());
    }
}
