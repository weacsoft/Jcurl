package com.jcurl2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.config.AppProperties;
import com.jcurl2.model.component.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cookie 管理服务 — 按集合 (Collection) 隔离的自动 Cookie 管理, 类似 Postman。
 * <p>
 * 从 HTTP 响应的 Set-Cookie 头解析并存储 Cookie, 在后续请求中按域名/路径匹配自动附带。
 * Cookie 跟随项目 (集合) 走: 每个集合独立维护一份 Cookie 存储, 切换集合时切换上下文,
 * 并持久化到 {@code <数据目录>/cookies/<collectionId>.json}。
 * <p>
 * 移植自 Swing 版 Jcurl, 适配 core 的 Header 列表模型与 AppProperties 配置。
 */
@Service
public class CookieService {

    private static final Logger log = LoggerFactory.getLogger(CookieService.class);

    /** 无集合上下文时使用的默认存储 key (全局 cookie) */
    public static final String GLOBAL_COLLECTION = "__global__";

    /** 按集合存储: collectionId -> (domain -> (name -> cookie)) */
    private final Map<String, Map<String, Map<String, CookieEntry>>> collectionStore = new ConcurrentHashMap<>();

    /** 当前集合 ID */
    private volatile String currentCollectionId = GLOBAL_COLLECTION;

    /** 持久化相关 (为 null 表示禁用持久化, 如测试场景) */
    private final ObjectMapper objectMapper;
    private final Path cookiesDir;

    /** 无参构造: 持久化禁用, 供测试与手动实例化使用。 */
    public CookieService() {
        this.objectMapper = null;
        this.cookiesDir = null;
    }

    /** Spring 注入构造: 启用按集合的 JSON 文件持久化。 */
    @Autowired
    public CookieService(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        Path base = resolveDataDir(properties);
        this.cookiesDir = base != null ? base.resolve("cookies") : null;
    }

    private Path resolveDataDir(AppProperties properties) {
        // 1. 系统属性 jcurl.data-dir 优先
        String sysProp = System.getProperty("jcurl.data-dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        // 2. 环境变量 JCURL_DATA_DIR
        String envVar = System.getenv("JCURL_DATA_DIR");
        if (envVar != null && !envVar.isBlank()) {
            return Path.of(envVar);
        }
        // 3. 配置文件中的 jcurl2.data-dir
        if (properties == null) {
            return null;
        }
        String dir = properties.getDataDir();
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        // 4. 默认: 当前工作目录下 .api-client
        return Path.of(System.getProperty("user.dir"), ".api-client");
    }

    /**
     * 切换当前集合上下文, 并从磁盘加载该集合的 Cookie。
     *
     * @param collectionId 集合 ID, null 时切换到全局上下文
     */
    public void setCurrentCollection(String collectionId) {
        String id = (collectionId == null || collectionId.trim().isEmpty())
                ? GLOBAL_COLLECTION : collectionId.trim();
        this.currentCollectionId = id;
        // 若内存中尚未加载该集合, 则从磁盘加载
        if (!collectionStore.containsKey(id)) {
            loadFromDisk(id);
        }
    }

    /** 获取当前集合 ID。 */
    public String getCurrentCollectionId() {
        return currentCollectionId;
    }

    // ===== 自动 Cookie 管理 (请求/响应) =====

    /**
     * 从响应头列表中解析 Set-Cookie 头, 提取 cookie 并存储到当前集合。
     *
     * @param url             请求 URL (用于推断 cookie 域名)
     * @param responseHeaders 响应头列表 (OkHttp 可能含多个同名 Set-Cookie)
     */
    public void storeFromResponse(String url, List<Header> responseHeaders) {
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            return;
        }
        String requestHost = extractHost(url);
        if (requestHost == null || requestHost.isEmpty()) {
            return;
        }
        for (Header header : responseHeaders) {
            if (header == null || header.getKey() == null) {
                continue;
            }
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            String headerValue = header.getValue();
            if (headerValue == null) {
                continue;
            }
            for (String cookieStr : headerValue.split("\n")) {
                if (cookieStr == null || cookieStr.trim().isEmpty()) {
                    continue;
                }
                try {
                    parseAndStore(requestHost, cookieStr.trim());
                } catch (Exception e) {
                    log.warn("解析 Set-Cookie 失败: {}", cookieStr, e);
                }
            }
        }
    }

    /** 返回适合该 URL 的 Cookie header 值 (从当前集合匹配)。无匹配返回 null。 */
    public String getCookiesForUrl(String url) {
        String host = extractHost(url);
        if (host == null || host.isEmpty()) {
            return null;
        }
        boolean secure = isSecureUrl(url);
        String path = extractPath(url);

        List<String> pairs = new ArrayList<>();
        long now = System.currentTimeMillis();

        Map<String, Map<String, CookieEntry>> store = getCurrentStore();
        for (Map.Entry<String, Map<String, CookieEntry>> domainEntry : store.entrySet()) {
            String cookieDomain = domainEntry.getKey();
            if (!domainMatches(host, cookieDomain)) {
                continue;
            }
            for (CookieEntry cookie : domainEntry.getValue().values()) {
                if (cookie.isExpired(now)) {
                    continue;
                }
                if (cookie.isSecure() && !secure) {
                    continue;
                }
                if (!pathMatches(path, cookie.getPath())) {
                    continue;
                }
                pairs.add(cookie.getName() + "=" + cookie.getValue());
            }
        }

        if (pairs.isEmpty()) {
            return null;
        }
        return String.join("; ", pairs);
    }

    // ===== CRUD (供界面树形表格管理) =====

    /** 返回当前集合的所有 cookie (domain -> (name -> cookie)) 快照。 */
    public Map<String, Map<String, CookieEntry>> getAllCookies() {
        Map<String, Map<String, CookieEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, CookieEntry>> entry : getCurrentStore().entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return result;
    }

    /** 返回当前集合的所有 cookie 扁平列表 (便于表格展示)。 */
    public List<CookieEntry> getAllCookiesFlat() {
        List<CookieEntry> result = new ArrayList<>();
        for (Map<String, CookieEntry> domainMap : getCurrentStore().values()) {
            result.addAll(domainMap.values());
        }
        return result;
    }

    /** 新增或覆盖一条 cookie (同 domain + name 覆盖)。 */
    public void addCookie(CookieEntry cookie) {
        if (cookie == null || cookie.getName() == null || cookie.getName().trim().isEmpty()) {
            return;
        }
        String domainKey = normalizeDomain(cookie.getDomain());
        if (domainKey.isEmpty()) {
            return;
        }
        cookie.setDomain(domainKey);
        if (cookie.getPath() == null || cookie.getPath().isEmpty()) {
            cookie.setPath("/");
        }
        getCurrentStore().computeIfAbsent(domainKey, k -> new ConcurrentHashMap<>())
                .put(cookie.getName(), cookie);
        persist();
    }

    /** 更新一条 cookie。若 domain/name 发生变化, 删除旧条目再写入新条目。 */
    public void updateCookie(String oldDomain, String oldName, CookieEntry newEntry) {
        if (newEntry == null || newEntry.getName() == null || newEntry.getName().trim().isEmpty()) {
            return;
        }
        Map<String, Map<String, CookieEntry>> store = getCurrentStore();
        String oldDomainKey = normalizeDomain(oldDomain);
        Map<String, CookieEntry> oldDomainMap = store.get(oldDomainKey);
        if (oldDomainMap != null) {
            oldDomainMap.remove(oldName);
            if (oldDomainMap.isEmpty()) {
                store.remove(oldDomainKey);
            }
        }
        addCookie(newEntry);
    }

    /** 删除指定 domain + name 的 cookie。 */
    public void deleteCookie(String domain, String name) {
        String domainKey = normalizeDomain(domain);
        Map<String, CookieEntry> domainMap = getCurrentStore().get(domainKey);
        if (domainMap != null) {
            domainMap.remove(name);
            if (domainMap.isEmpty()) {
                getCurrentStore().remove(domainKey);
            }
            persist();
        }
    }

    /** 删除指定 domain 下的所有 cookie。 */
    public void deleteDomain(String domain) {
        String domainKey = normalizeDomain(domain);
        if (getCurrentStore().remove(domainKey) != null) {
            persist();
        }
    }

    /** 清空当前集合的所有 cookie。 */
    public void clearAll() {
        getCurrentStore().clear();
        persist();
    }

    /** 统计当前集合的 cookie 总数。 */
    public int getCookieCount() {
        int count = 0;
        for (Map<String, CookieEntry> domainMap : getCurrentStore().values()) {
            count += domainMap.size();
        }
        return count;
    }

    // ===== 内部方法 =====

    private Map<String, Map<String, CookieEntry>> getCurrentStore() {
        return collectionStore.computeIfAbsent(currentCollectionId, k -> new ConcurrentHashMap<>());
    }

    private void parseAndStore(String requestHost, String cookieStr) {
        String[] parts = cookieStr.split(";");
        if (parts.length == 0) {
            return;
        }
        String firstPart = parts[0].trim();
        int eq = firstPart.indexOf('=');
        if (eq < 0) {
            return;
        }
        String name = firstPart.substring(0, eq).trim();
        if (name.isEmpty()) {
            return;
        }
        String value = firstPart.substring(eq + 1).trim();

        String domain = null;
        String path = "/";
        Long expiresAt = null;
        Long maxAgeExpiry = null;
        boolean secure = false;
        boolean httpOnly = false;

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            int attrEq = part.indexOf('=');
            String attrName;
            String attrValue;
            if (attrEq >= 0) {
                attrName = part.substring(0, attrEq).trim();
                attrValue = part.substring(attrEq + 1).trim();
            } else {
                attrName = part;
                attrValue = "";
            }
            String attrLower = attrName.toLowerCase(Locale.ROOT);
            if ("domain".equals(attrLower)) {
                domain = attrValue;
            } else if ("path".equals(attrLower)) {
                path = attrValue.isEmpty() ? "/" : attrValue;
            } else if ("expires".equals(attrLower)) {
                long exp = parseExpiry(attrValue);
                if (exp > 0) {
                    expiresAt = exp;
                }
            } else if ("max-age".equals(attrLower)) {
                maxAgeExpiry = parseMaxAge(attrValue);
            } else if ("secure".equals(attrLower)) {
                secure = true;
            } else if ("httponly".equals(attrLower)) {
                httpOnly = true;
            }
        }

        String domainKey;
        if (domain != null && !domain.isEmpty()) {
            domainKey = normalizeDomain(domain);
        } else {
            domainKey = normalizeDomain(requestHost);
        }
        if (domainKey.isEmpty()) {
            return;
        }

        long expiry = 0;
        if (maxAgeExpiry != null) {
            if (maxAgeExpiry > 0) {
                expiry = maxAgeExpiry;
            } else if (maxAgeExpiry == -1L) {
                expiry = 1L;
            }
        } else if (expiresAt != null) {
            expiry = expiresAt;
        }

        if (value.isEmpty() || "deleted".equalsIgnoreCase(value)
                || (expiry > 0 && System.currentTimeMillis() > expiry)) {
            Map<String, CookieEntry> domainMap = getCurrentStore().get(domainKey);
            if (domainMap != null) {
                domainMap.remove(name);
                if (domainMap.isEmpty()) {
                    getCurrentStore().remove(domainKey);
                }
            }
            persist();
            return;
        }

        CookieEntry entry = new CookieEntry();
        entry.setName(name);
        entry.setValue(value);
        entry.setDomain(domainKey);
        entry.setPath(path);
        entry.setExpiry(expiry);
        entry.setSecure(secure);
        entry.setHttpOnly(httpOnly);
        getCurrentStore().computeIfAbsent(domainKey, k -> new ConcurrentHashMap<>()).put(name, entry);
        persist();
    }

    // ===== 持久化 =====

    private void loadFromDisk(String collectionId) {
        if (objectMapper == null || cookiesDir == null) {
            return;
        }
        Path file = cookiesDir.resolve(safeFileName(collectionId) + ".json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            CookieFileData data = objectMapper.readValue(file.toFile(), CookieFileData.class);
            Map<String, Map<String, CookieEntry>> store = new ConcurrentHashMap<>();
            if (data != null && data.getCookies() != null) {
                for (CookieEntry cookie : data.getCookies()) {
                    String domainKey = normalizeDomain(cookie.getDomain());
                    if (domainKey.isEmpty() || cookie.getName() == null) {
                        continue;
                    }
                    cookie.setDomain(domainKey);
                    if (cookie.getPath() == null || cookie.getPath().isEmpty()) {
                        cookie.setPath("/");
                    }
                    store.computeIfAbsent(domainKey, k -> new ConcurrentHashMap<>())
                            .put(cookie.getName(), cookie);
                }
            }
            collectionStore.put(collectionId, store);
        } catch (IOException e) {
            log.warn("加载 Cookie 文件失败 {}: {}", file, e.getMessage());
        }
    }

    private void persist() {
        if (objectMapper == null || cookiesDir == null) {
            return;
        }
        try {
            Files.createDirectories(cookiesDir);
            Path file = cookiesDir.resolve(safeFileName(currentCollectionId) + ".json");
            CookieFileData data = new CookieFileData();
            data.setCollectionId(currentCollectionId);
            data.setCookies(getAllCookiesFlat());
            objectMapper.writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.warn("保存 Cookie 失败: {}", e.getMessage());
        }
    }

    private String safeFileName(String id) {
        if (id == null) {
            return "global";
        }
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // ===== 匹配与解析辅助 =====

    private boolean domainMatches(String host, String cookieDomain) {
        if (host == null || host.isEmpty() || cookieDomain == null || cookieDomain.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String d = cookieDomain.toLowerCase(Locale.ROOT);
        while (d.startsWith(".")) {
            d = d.substring(1);
        }
        if (d.isEmpty()) {
            return false;
        }
        return h.equals(d) || h.endsWith("." + d);
    }

    private boolean pathMatches(String requestPath, String cookiePath) {
        if (cookiePath == null || cookiePath.isEmpty() || "/".equals(cookiePath)) {
            return true;
        }
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        if (requestPath.startsWith(cookiePath) && cookiePath.endsWith("/")) {
            return true;
        }
        if (requestPath.startsWith(cookiePath)
                && requestPath.length() > cookiePath.length()
                && requestPath.charAt(cookiePath.length()) == '/') {
            return true;
        }
        return false;
    }

    private String normalizeDomain(String domain) {
        if (domain == null) {
            return "";
        }
        String d = domain.trim().toLowerCase(Locale.ROOT);
        while (d.startsWith(".")) {
            d = d.substring(1);
        }
        return d;
    }

    private String extractHost(String url) {
        URL parsed = parseUrl(url);
        return parsed != null ? parsed.getHost() : "";
    }

    private String extractPath(String url) {
        URL parsed = parseUrl(url);
        if (parsed == null) {
            return "/";
        }
        String p = parsed.getPath();
        return (p == null || p.isEmpty()) ? "/" : p;
    }

    private boolean isSecureUrl(String url) {
        URL parsed = parseUrl(url);
        return parsed != null && "https".equalsIgnoreCase(parsed.getProtocol());
    }

    private URL parseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            return new URL(url.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private long parseExpiry(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return 0;
        }
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd-MMM-yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date date = sdf.parse(dateStr.trim());
                if (date != null) {
                    return date.getTime();
                }
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return 0;
    }

    private long parseMaxAge(String maxAgeStr) {
        if (maxAgeStr == null || maxAgeStr.trim().isEmpty()) {
            return 0;
        }
        try {
            long maxAge = Long.parseLong(maxAgeStr.trim());
            if (maxAge == 0) {
                return -1L;
            }
            if (maxAge < 0) {
                return 0L;
            }
            return System.currentTimeMillis() + maxAge * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Cookie 文件持久化包装对象。 */
    public static class CookieFileData {
        private String collectionId;
        private List<CookieEntry> cookies = new ArrayList<>();

        public String getCollectionId() {
            return collectionId;
        }

        public void setCollectionId(String collectionId) {
            this.collectionId = collectionId;
        }

        public List<CookieEntry> getCookies() {
            return cookies;
        }

        public void setCookies(List<CookieEntry> cookies) {
            this.cookies = cookies != null ? cookies : new ArrayList<>();
        }
    }

    /**
     * Cookie 条目。
     * expiry 为 0 表示 session cookie; expiry 大于 0 表示持久 cookie 的过期时间戳 (毫秒)。
     */
    public static class CookieEntry {

        private String name;
        private String value;
        private String domain;
        private String path;
        private long expiry;
        private boolean secure;
        private boolean httpOnly;

        public CookieEntry() {
        }

        public CookieEntry(String name, String value, String domain, String path) {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
        }

        public boolean isExpired() {
            return expiry > 0 && System.currentTimeMillis() > expiry;
        }

        public boolean isExpired(long now) {
            return expiry > 0 && now > expiry;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getExpiry() {
            return expiry;
        }

        public void setExpiry(long expiry) {
            this.expiry = expiry;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public boolean isHttpOnly() {
            return httpOnly;
        }

        public void setHttpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
        }
    }
}
