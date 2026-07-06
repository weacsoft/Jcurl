package com.jpostman2.model.component;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 认证配置。
 * <p>
 * type 取值:
 * <ul>
 *   <li>none — 无认证</li>
 *   <li>basic — Basic Auth(data: username, password)</li>
 *   <li>bearer — Bearer Token(data: token)</li>
 *   <li>apikey — API Key(data: key, value, addTo[header/query])</li>
 *   <li>inherit — 继承集合级认证(仅 RequestNode 使用)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthConfig {

    public enum Type { NONE, BASIC, BEARER, APIKEY, INHERIT }

    private String type = "none";
    private String username;   // basic
    private String password;   // basic
    private String token;      // bearer
    private String key;        // apikey
    private String value;      // apikey
    private String addTo = "header";  // apikey: header / query

    public AuthConfig() {}

    public AuthConfig(String type) {
        this.type = type;
    }

    public static AuthConfig none() {
        return new AuthConfig("none");
    }

    public static AuthConfig inherit() {
        return new AuthConfig("inherit");
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getAddTo() { return addTo; }
    public void setAddTo(String addTo) { this.addTo = addTo; }
}
