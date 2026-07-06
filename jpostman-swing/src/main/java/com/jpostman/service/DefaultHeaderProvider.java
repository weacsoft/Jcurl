package com.jpostman.service;

import com.jpostman.model.AuthConfig;
import com.jpostman.model.KeyValue;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认请求头提供者 (核心层, 不依赖任何 UI)。
 * <p>
 * 负责:
 * <ul>
 *   <li>计算随请求自动带上的默认头 (类似 Postman 的隐藏默认头): Accept / User-Agent /
 *       Accept-Encoding / Accept-Language / Cache-Control, 以及根据 body 类型推导的 Content-Type。</li>
 *   <li>计算认证派生头 (Basic / Bearer / API Key in header), 仅用于界面展示。</li>
 *   <li>将默认头与用户自定义头合并 —— 用户同名头覆盖默认头。</li>
 * </ul>
 * 真正"发送什么头"由 {@link HttpEngineService} 在发送时调用本类合并得出,
 * 界面层只负责采集用户输入与展示, 从而实现核心/界面分离。
 */
@Component
public class DefaultHeaderProvider {

    /** 标记为自动生成的头描述, 同时用于界面区分只读行。 */
    public static final String AUTO_DESCRIPTION = "自动";

    private static final String CT = "Content-Type";
    private static final String AUTH = "Authorization";

    /**
     * 5 个静态默认头 (始终启用)。
     */
    public List<KeyValue> getStaticDefaults() {
        List<KeyValue> list = new ArrayList<>();
        list.add(new KeyValue("Accept", "*/*", AUTO_DESCRIPTION, true));
        list.add(new KeyValue("User-Agent", "JPostman/0.1", AUTO_DESCRIPTION, true));
        // 只声明 gzip/deflate: 由引擎侧统一解压, 避免出现无法解码的 br 编码。
        list.add(new KeyValue("Accept-Encoding", "gzip, deflate", AUTO_DESCRIPTION, true));
        list.add(new KeyValue("Accept-Language", "zh-CN,zh;q=0.9", AUTO_DESCRIPTION, true));
        list.add(new KeyValue("Cache-Control", "no-cache", AUTO_DESCRIPTION, true));
        return list;
    }

    /**
     * 根据 body 类型推导 Content-Type, 无对应类型时返回 null。
     *
     * @param bodyType        body 类型: none/raw/urlencoded/form-data/binary
     * @param rawContentType  raw 模式下用户选择的内容类型
     */
    public String getBodyContentType(String bodyType, String rawContentType) {
        if (bodyType == null) {
            return null;
        }
        String t = bodyType.trim().toLowerCase();
        switch (t) {
            case "urlencoded":
                return "application/x-www-form-urlencoded";
            case "form-data":
                return "multipart/form-data";
            case "raw":
                if (rawContentType != null && !rawContentType.trim().isEmpty()) {
                    return rawContentType.trim();
                }
                return null;
            case "binary":
                return "application/octet-stream";
            default:
                return null;
        }
    }

    /**
     * 计算随请求发送的自动头 (5 个静态默认 + body 推导的 Content-Type)。
     * 不包含认证头 —— 认证头由引擎从 {@link AuthConfig} 单独处理。
     */
    public List<KeyValue> computeAutoHeaders(String bodyType, String rawContentType) {
        List<KeyValue> list = new ArrayList<>(getStaticDefaults());
        String bodyCt = getBodyContentType(bodyType, rawContentType);
        if (bodyCt != null) {
            list.add(new KeyValue(CT, bodyCt, AUTO_DESCRIPTION, true));
        }
        return list;
    }

    /**
     * 计算认证派生头 (仅用于界面展示)。引擎实际发送时从 AuthConfig 取值, 不依赖此方法。
     *
     * @return 认证头 KeyValue, 无则 null
     */
    public KeyValue getAuthHeader(AuthConfig auth) {
        if (auth == null || auth.isEmpty()) {
            return null;
        }
        String type = auth.getType() == null ? "" : auth.getType().trim().toLowerCase();
        switch (type) {
            case "basic": {
                String user = auth.getBasicUsername() == null ? "" : auth.getBasicUsername();
                String pass = auth.getBasicPassword() == null ? "" : auth.getBasicPassword();
                if (user.isEmpty() && pass.isEmpty()) {
                    return null;
                }
                String raw = user + ":" + pass;
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                return new KeyValue(AUTH, "Basic " + encoded, AUTO_DESCRIPTION, true);
            }
            case "bearer": {
                String token = auth.getBearerToken() == null ? "" : auth.getBearerToken();
                if (token.trim().isEmpty()) {
                    return null;
                }
                return new KeyValue(AUTH, "Bearer " + token, AUTO_DESCRIPTION, true);
            }
            case "apikey": {
                String in = auth.getApiKeyIn() == null ? "header" : auth.getApiKeyIn().trim().toLowerCase();
                if (!"header".equals(in)) {
                    // query/cookie 模式不产生请求头
                    return null;
                }
                String name = auth.getApiKeyName() == null ? "" : auth.getApiKeyName().trim();
                if (name.isEmpty()) {
                    return null;
                }
                String value = auth.getApiKeyValue() == null ? "" : auth.getApiKeyValue();
                return new KeyValue(name, value, AUTO_DESCRIPTION, true);
            }
            default:
                return null;
        }
    }

    /**
     * 计算界面展示用的自动头 = 发送自动头 + 认证派生头 (若有)。
     */
    public List<KeyValue> computeDisplayAutoHeaders(String bodyType, String rawContentType, AuthConfig auth) {
        List<KeyValue> list = new ArrayList<>(computeAutoHeaders(bodyType, rawContentType));
        KeyValue authHeader = getAuthHeader(auth);
        if (authHeader != null) {
            list.add(authHeader);
        }
        return list;
    }

    /**
     * 合并默认头与用户头: 用户同名头覆盖默认头 (键名忽略大小写)。
     * 仅保留启用的项; 跳过键为空的用户项。顺序: 默认头在前, 用户新增项追加在后。
     *
     * @param autoHeaders 自动默认头 (始终启用)
     * @param userHeaders 用户自定义头 (可能含禁用项)
     */
    public List<KeyValue> mergeEffective(List<KeyValue> autoHeaders, List<KeyValue> userHeaders) {
        // LinkedHashMap 保留插入顺序, 键名小写用于去重
        Map<String, KeyValue> merged = new LinkedHashMap<>();
        if (autoHeaders != null) {
            for (KeyValue h : autoHeaders) {
                if (h == null || h.getKey() == null || h.getKey().trim().isEmpty()) {
                    continue;
                }
                if (!h.isEnabled()) {
                    continue;
                }
                merged.put(h.getKey().trim().toLowerCase(), copy(h));
            }
        }
        if (userHeaders != null) {
            for (KeyValue h : userHeaders) {
                if (h == null || h.getKey() == null || h.getKey().trim().isEmpty()) {
                    continue;
                }
                if (!h.isEnabled()) {
                    // 用户禁用的同名头也覆盖默认头 -> 等价于不发送该头
                    merged.remove(h.getKey().trim().toLowerCase());
                    continue;
                }
                // 用户头覆盖默认头, 描述去掉"自动"标记
                KeyValue userCopy = new KeyValue(h.getKey().trim(), h.getValue(), h.getDescription(), true);
                merged.put(h.getKey().trim().toLowerCase(), userCopy);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private KeyValue copy(KeyValue h) {
        return new KeyValue(h.getKey(), h.getValue(), h.getDescription(), h.isEnabled());
    }
}
