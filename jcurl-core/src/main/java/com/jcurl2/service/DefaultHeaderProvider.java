package com.jcurl2.service;

import com.jcurl2.model.component.AuthConfig;
import com.jcurl2.model.component.Header;
import com.jcurl2.model.component.RequestBody;
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
 * <p>
 * 移植自 Swing 版 Jcurl, 适配 core 的 Header / AuthConfig / RequestBody 模型。
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
    public List<Header> getStaticDefaults() {
        List<Header> list = new ArrayList<>();
        list.add(autoHeader("Accept", "*/*"));
        list.add(autoHeader("User-Agent", "Jcurl/0.1"));
        // 只声明 gzip/deflate: 由引擎侧统一解压, 避免出现无法解码的 br 编码。
        list.add(autoHeader("Accept-Encoding", "gzip, deflate"));
        list.add(autoHeader("Accept-Language", "zh-CN,zh;q=0.9"));
        list.add(autoHeader("Cache-Control", "no-cache"));
        return list;
    }

    /**
     * 根据 body 类型推导 Content-Type, 无对应类型时返回 null。
     *
     * @param bodyType        body 类型: none/raw/x-www-form-urlencoded/form-data/binary
     * @param rawContentType  raw 模式下用户选择的内容类型 (完整 media type, 如 application/json; charset=utf-8)
     */
    public String getBodyContentType(String bodyType, String rawContentType) {
        if (bodyType == null) {
            return null;
        }
        String t = bodyType.trim().toLowerCase();
        switch (t) {
            case "x-www-form-urlencoded":
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
    public List<Header> computeAutoHeaders(String bodyType, String rawContentType) {
        List<Header> list = new ArrayList<>(getStaticDefaults());
        String bodyCt = getBodyContentType(bodyType, rawContentType);
        if (bodyCt != null) {
            list.add(autoHeader(CT, bodyCt));
        }
        return list;
    }

    /**
     * 计算认证派生头 (仅用于界面展示)。引擎实际发送时从 AuthConfig 取值, 不依赖此方法。
     *
     * @return 认证头 Header, 无则 null
     */
    public Header getAuthHeader(AuthConfig auth) {
        if (auth == null || auth.getType() == null) {
            return null;
        }
        String type = auth.getType().trim().toLowerCase();
        switch (type) {
            case "basic": {
                String user = auth.getUsername() == null ? "" : auth.getUsername();
                String pass = auth.getPassword() == null ? "" : auth.getPassword();
                if (user.isEmpty() && pass.isEmpty()) {
                    return null;
                }
                String raw = user + ":" + pass;
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                return autoHeader(AUTH, "Basic " + encoded);
            }
            case "bearer": {
                String token = auth.getToken() == null ? "" : auth.getToken();
                if (token.trim().isEmpty()) {
                    return null;
                }
                return autoHeader(AUTH, "Bearer " + token);
            }
            case "apikey": {
                String in = auth.getAddTo() == null ? "header" : auth.getAddTo().trim().toLowerCase();
                if (!"header".equals(in)) {
                    // query 模式不产生请求头
                    return null;
                }
                String name = auth.getKey() == null ? "" : auth.getKey().trim();
                if (name.isEmpty()) {
                    return null;
                }
                String value = auth.getValue() == null ? "" : auth.getValue();
                return autoHeader(name, value);
            }
            default:
                return null;
        }
    }

    /**
     * 计算界面展示用的自动头 = 发送自动头 + 认证派生头 (若有)。
     */
    public List<Header> computeDisplayAutoHeaders(String bodyType, String rawContentType, AuthConfig auth) {
        List<Header> list = new ArrayList<>(computeAutoHeaders(bodyType, rawContentType));
        Header authHeader = getAuthHeader(auth);
        if (authHeader != null) {
            list.add(authHeader);
        }
        return list;
    }

    /**
     * 合并默认头与用户头: 用户同名头覆盖默认头 (键名忽略大小写)。
     * 仅保留启用的项; 跳过键为空的用户项。顺序: 默认头在前, 用户新增项追加在后。
     * 用户禁用的同名头等于删除默认头 (不发送)。
     *
     * @param autoHeaders 自动默认头 (始终启用)
     * @param userHeaders 用户自定义头 (可能含禁用项)
     */
    public List<Header> mergeEffective(List<Header> autoHeaders, List<Header> userHeaders) {
        // LinkedHashMap 保留插入顺序, 键名小写用于去重
        Map<String, Header> merged = new LinkedHashMap<>();
        if (autoHeaders != null) {
            for (Header h : autoHeaders) {
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
            for (Header h : userHeaders) {
                if (h == null || h.getKey() == null || h.getKey().trim().isEmpty()) {
                    continue;
                }
                String lowerKey = h.getKey().trim().toLowerCase();
                if (!h.isEnabled()) {
                    // 用户禁用的同名头也覆盖默认头 -> 等价于不发送该头
                    merged.remove(lowerKey);
                    continue;
                }
                // 用户头覆盖默认头, 描述去掉"自动"标记
                Header userCopy = new Header(h.getKey().trim(), h.getValue());
                userCopy.setEnabled(true);
                userCopy.setDescription(h.getDescription());
                merged.put(lowerKey, userCopy);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 根据当前 body 配置解析 raw 模式对应的完整 Content-Type (供 computeAutoHeaders 使用)。 */
    public String resolveRawContentType(RequestBody body) {
        if (body == null || !"raw".equals(body.getType())) {
            return null;
        }
        String rawType = body.getRawType() != null ? body.getRawType() : "text";
        switch (rawType) {
            case "json":
                return "application/json; charset=utf-8";
            case "xml":
                return "application/xml; charset=utf-8";
            case "html":
                return "text/html; charset=utf-8";
            case "text":
            default:
                return "text/plain; charset=utf-8";
        }
    }

    private Header autoHeader(String key, String value) {
        Header h = new Header(key, value);
        h.setEnabled(true);
        h.setDescription(AUTO_DESCRIPTION);
        return h;
    }

    private Header copy(Header h) {
        Header c = new Header(h.getKey(), h.getValue());
        c.setEnabled(h.isEnabled());
        c.setDescription(h.getDescription());
        return c;
    }
}
