package com.jcurl2.service;

import com.jcurl2.model.Collection;
import com.jcurl2.model.RequestNode;
import com.jcurl2.model.component.AuthConfig;
import com.jcurl2.model.component.Header;
import com.jcurl2.model.component.QueryParam;
import com.jcurl2.model.component.RequestBody;
import com.jcurl2.model.dto.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集合继承合并器 — 将集合级配置(baseUrl / headers / auth)合并到请求节点,生成最终执行配置。
 * <p>
 * 合并规则:
 * <ul>
 *   <li><b>URL</b>: 请求 URL 为绝对路径时直接使用;否则拼接集合 baseUrl + 请求相对路径</li>
 *   <li><b>Headers</b>: 集合级 Headers 先注入,请求级同名 Header 覆盖集合级(key 不区分大小写)</li>
 *   <li><b>Auth</b>: 请求 auth.type=inherit 时继承集合级 auth;集合级也为 inherit/null 时回退为 none</li>
 *   <li><b>Params / Body</b>: 直接从请求节点复制,不涉及集合级合并</li>
 * </ul>
 */
@Service
public class InheritanceMerger {

    private static final Logger log = LoggerFactory.getLogger(InheritanceMerger.class);

    /**
     * 将集合级配置合并到请求节点,生成最终执行配置。
     * <p>
     * 此方法不解析变量 — 变量解析由调用方在合并后通过 {@link VariableResolver} 完成。
     *
     * @param request    请求节点(集合树中的持久化节点)
     * @param collection 所属集合(提供 baseUrl / headers / auth 继承源)
     * @return 合并后的 RequestConfig(已含最终 URL / Headers / Auth / Params / Body)
     */
    public RequestConfig merge(RequestNode request, Collection collection) {
        RequestConfig config = new RequestConfig();

        // 1. Method
        config.setMethod(request.getMethod() != null ? request.getMethod() : "GET");

        // 2. URL 合并
        config.setUrl(mergeUrl(request.getUrl(), collection.getBaseUrl()));

        // 3. Params 直接复制
        config.setParams(copyParams(request.getParams()));

        // 4. Headers 合并(集合级 + 请求级,同名请求级覆盖)
        config.setHeaders(mergeHeaders(collection.getHeaders(), request.getHeaders()));

        // 5. Body 直接复制
        config.setBody(copyBody(request.getBody()));

        // 6. Auth 合并(inherit → 集合级 → none)
        config.setAuth(mergeAuth(request.getAuth(), collection.getAuth()));

        log.debug("合并请求配置: request={}, url={}, headers={}, auth={}",
                request.getName(), config.getUrl(), config.getHeaders().size(),
                config.getAuth().getType());

        return config;
    }

    /**
     * 批量合并集合中所有请求(用于批量执行/性能测试)。
     *
     * @param collection 目标集合
     * @return 合并后的 RequestConfig 列表,顺序与集合中请求顺序一致
     */
    public List<RequestConfig> mergeAll(Collection collection) {
        List<RequestConfig> result = new ArrayList<>();
        for (RequestNode request : collection.getAllRequests()) {
            result.add(merge(request, collection));
        }
        return result;
    }

    // ==================== URL 合并 ====================

    /**
     * 合并请求 URL 与集合 baseUrl。
     * <p>
     * 规则:
     * <ul>
     *   <li>请求 URL 为绝对路径(http:// / https://) → 直接使用</li>
     *   <li>baseUrl 为空 → 直接使用请求 URL</li>
     *   <li>否则 → 拼接 baseUrl + 请求 URL(处理斜杠边界)</li>
     * </ul>
     *
     * @param requestUrl 请求 URL(可能为相对路径)
     * @param baseUrl    集合级 baseUrl(可能为 null)
     * @return 合并后的完整 URL
     */
    String mergeUrl(String requestUrl, String baseUrl) {
        if (requestUrl == null || requestUrl.isBlank()) {
            return baseUrl != null ? baseUrl : "";
        }

        // 绝对 URL 直接使用
        if (isAbsoluteUrl(requestUrl)) {
            return requestUrl;
        }

        // baseUrl 为空,直接使用请求 URL
        if (baseUrl == null || baseUrl.isBlank()) {
            return requestUrl;
        }

        // 拼接 baseUrl + 相对路径
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = requestUrl.startsWith("/") ? requestUrl : "/" + requestUrl;
        return base + path;
    }

    private boolean isAbsoluteUrl(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    // ==================== Headers 合并 ====================

    /**
     * 合并集合级 Headers 与请求级 Headers。
     * <p>
     * 规则:
     * <ul>
     *   <li>先注入集合级 Headers</li>
     *   <li>再注入请求级 Headers,同名 Key(key 不区分大小写)覆盖集合级</li>
     *   <li>保留 enabled 状态 — 集合级 Header 被请求级同名 Header 覆盖时,以请求级 enabled 为准</li>
     * </ul>
     *
     * @param collectionHeaders 集合级 Headers(可能为空)
     * @param requestHeaders    请求级 Headers(可能为空)
     * @return 合并后的 Headers 列表
     */
    List<Header> mergeHeaders(List<Header> collectionHeaders, List<Header> requestHeaders) {
        // 使用 LinkedHashMap 保持插入顺序,key 统一转小写用于去重
        Map<String, Header> merged = new LinkedHashMap<>();

        // 先放入集合级
        if (collectionHeaders != null) {
            for (Header h : collectionHeaders) {
                if (h.getKey() != null && !h.getKey().isBlank()) {
                    merged.put(h.getKey().toLowerCase(), copyHeader(h));
                }
            }
        }

        // 请求级覆盖同名 key
        if (requestHeaders != null) {
            for (Header h : requestHeaders) {
                if (h.getKey() != null && !h.getKey().isBlank()) {
                    merged.put(h.getKey().toLowerCase(), copyHeader(h));
                }
            }
        }

        return new ArrayList<>(merged.values());
    }

    private Header copyHeader(Header src) {
        Header copy = new Header();
        copy.setKey(src.getKey());
        copy.setValue(src.getValue());
        copy.setEnabled(src.isEnabled());
        copy.setDescription(src.getDescription());
        return copy;
    }

    // ==================== Auth 合并 ====================

    /**
     * 合并请求级 Auth 与集合级 Auth。
     * <p>
     * 规则:
     * <ul>
     *   <li>请求 auth.type=inherit → 使用集合级 auth</li>
     *   <li>集合级 auth 为 null 或 type=inherit → 回退为 none</li>
     *   <li>否则 → 使用请求自身 auth</li>
     * </ul>
     *
     * @param requestAuth    请求级 Auth
     * @param collectionAuth 集合级 Auth(可能为 null)
     * @return 合并后的 AuthConfig
     */
    AuthConfig mergeAuth(AuthConfig requestAuth, AuthConfig collectionAuth) {
        if (requestAuth == null) {
            return AuthConfig.none();
        }

        String requestType = requestAuth.getType();
        if (requestType == null || "inherit".equals(requestType)) {
            // 继承集合级
            if (collectionAuth != null) {
                String collectionType = collectionAuth.getType();
                if (collectionType != null && !"inherit".equals(collectionType)) {
                    return copyAuth(collectionAuth);
                }
            }
            // 集合级也为 inherit 或 null → none
            return AuthConfig.none();
        }

        // 使用请求自身 auth
        return copyAuth(requestAuth);
    }

    private AuthConfig copyAuth(AuthConfig src) {
        AuthConfig copy = new AuthConfig();
        copy.setType(src.getType());
        copy.setUsername(src.getUsername());
        copy.setPassword(src.getPassword());
        copy.setToken(src.getToken());
        copy.setKey(src.getKey());
        copy.setValue(src.getValue());
        copy.setAddTo(src.getAddTo());
        return copy;
    }

    // ==================== 深拷贝工具 ====================

    private List<QueryParam> copyParams(List<QueryParam> src) {
        List<QueryParam> result = new ArrayList<>();
        if (src != null) {
            for (QueryParam p : src) {
                QueryParam copy = new QueryParam();
                copy.setKey(p.getKey());
                copy.setValue(p.getValue());
                copy.setEnabled(p.isEnabled());
                copy.setDescription(p.getDescription());
                result.add(copy);
            }
        }
        return result;
    }

    private RequestBody copyBody(RequestBody src) {
        if (src == null) {
            return RequestBody.none();
        }
        RequestBody copy = new RequestBody();
        copy.setType(src.getType());
        copy.setContent(src.getContent());
        copy.setRawType(src.getRawType());
        copy.setFilePath(src.getFilePath());
        // 深拷贝 formItems
        if (src.getFormItems() != null) {
            src.getFormItems().forEach(item -> copy.getFormItems().add(item));
        }
        return copy;
    }
}
