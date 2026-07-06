package com.jpostman2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jpostman2.model.Collection;
import com.jpostman2.model.CollectionItem;
import com.jpostman2.model.FolderNode;
import com.jpostman2.model.RequestNode;
import com.jpostman2.model.component.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入导出服务 — 支持 Postman v2.1、OpenAPI 3.0、cURL 格式。
 * <p>
 * 导入:
 * <ul>
 *   <li>{@link #importPostmanV21} — Postman v2.1 Collection JSON → Collection</li>
 *   <li>{@link #importOpenApi30} — OpenAPI 3.0 JSON → List&lt;Collection&gt;(按 tag 分组)</li>
 *   <li>{@link #importCurl} — cURL 命令 → RequestNode</li>
 * </ul>
 * 导出:
 * <ul>
 *   <li>{@link #exportPostmanV21} — Collection → Postman v2.1 JSON</li>
 * </ul>
 */
@Service
public class ImportExportService {

    private static final Logger log = LoggerFactory.getLogger(ImportExportService.class);

    private final ObjectMapper objectMapper;
    private final CollectionService collectionService;

    public ImportExportService(ObjectMapper objectMapper, CollectionService collectionService) {
        this.objectMapper = objectMapper;
        this.collectionService = collectionService;
    }

    // ==================== Postman v2.1 导入 ====================

    /**
     * 导入 Postman v2.1 Collection JSON。
     * <p>
     * 支持两种格式:
     * <ul>
     *   <li>带 "collection" 包装的导出格式</li>
     *   <li>直接的 Collection 格式(info + item)</li>
     * </ul>
     *
     * @param jsonContent Postman v2.1 JSON 字符串
     * @return 导入的 Collection(已持久化)
     */
    public Collection importPostmanV21(String jsonContent) throws Exception {
        JsonNode root = objectMapper.readTree(jsonContent);

        // 兼容带包装的导出格式
        JsonNode collectionNode = root.has("collection") ? root.get("collection") : root;

        // 集合名称
        String name = "导入的集合";
        if (collectionNode.has("info") && collectionNode.get("info").has("name")) {
            name = collectionNode.get("info").get("name").asText();
        }

        // 创建集合
        Collection collection = collectionService.createCollection(name);

        // 解析 item 列表
        if (collectionNode.has("item")) {
            JsonNode items = collectionNode.get("item");
            for (JsonNode item : items) {
                parsePostmanItem(item, collection, collection.getItems());
            }
        }

        // 解析集合级变量
        if (collectionNode.has("variable")) {
            JsonNode variables = collectionNode.get("variable");
            for (JsonNode var : variables) {
                Variable v = new Variable(
                        var.has("key") ? var.get("key").asText() : "",
                        var.has("value") ? var.get("value").asText() : "");
                collection.getVariables().add(v);
            }
        }

        // 保存
        collectionService.saveCollection(collection);
        log.info("Postman v2.1 导入完成: name={}, items={}", name, collection.getItems().size());
        return collection;
    }

    /** 递归解析 Postman item(可能是 Folder 或 Request) */
    private void parsePostmanItem(JsonNode item, Collection collection, List<CollectionItem> parentList) {
        String name = item.has("name") ? item.get("name").asText() : "未命名";

        // 如果有 item 字段 → Folder
        if (item.has("item") && !item.has("request")) {
            FolderNode folder = new FolderNode(UUID.randomUUID().toString(), name);
            parentList.add(folder);
            for (JsonNode child : item.get("item")) {
                parsePostmanItem(child, collection, folder.getItems());
            }
            return;
        }

        // 否则是 Request
        RequestNode request = new RequestNode(UUID.randomUUID().toString(), name, "GET", "");
        if (item.has("request")) {
            parsePostmanRequest(item.get("request"), request);
        }
        parentList.add(request);
    }

    /** 解析 Postman request 节点 */
    private void parsePostmanRequest(JsonNode requestNode, RequestNode request) {
        // Method
        if (requestNode.has("method")) {
            request.setMethod(requestNode.get("method").asText());
        }

        // URL
        if (requestNode.has("url")) {
            JsonNode urlNode = requestNode.get("url");
            if (urlNode.isTextual()) {
                request.setUrl(urlNode.asText());
            } else if (urlNode.has("raw")) {
                request.setUrl(urlNode.get("raw").asText());
            }

            // Query params
            if (urlNode.has("query")) {
                for (JsonNode q : urlNode.get("query")) {
                    QueryParam param = new QueryParam();
                    param.setKey(q.has("key") ? q.get("key").asText() : "");
                    param.setValue(q.has("value") ? q.get("value").asText() : "");
                    param.setEnabled(!q.has("disabled") || !q.get("disabled").asBoolean());
                    request.getParams().add(param);
                }
            }
        }

        // Headers
        if (requestNode.has("header")) {
            for (JsonNode h : requestNode.get("header")) {
                Header header = new Header();
                header.setKey(h.has("key") ? h.get("key").asText() : "");
                header.setValue(h.has("value") ? h.get("value").asText() : "");
                header.setEnabled(!h.has("disabled") || !h.get("disabled").asBoolean());
                request.getHeaders().add(header);
            }
        }

        // Body
        if (requestNode.has("body")) {
            JsonNode bodyNode = requestNode.get("body");
            String mode = bodyNode.has("mode") ? bodyNode.get("mode").asText() : "raw";
            RequestBody body = request.getBody();
            body.setType(switch (mode) {
                case "urlencoded" -> "x-www-form-urlencoded";
                case "formdata" -> "form-data";
                case "raw" -> "raw";
                case "file" -> "binary";
                default -> "none";
            });

            if ("raw".equals(mode) && bodyNode.has("raw")) {
                body.setContent(bodyNode.get("raw").asText());
                // 推断 rawType
                String lang = bodyNode.has("options") && bodyNode.get("options").has("raw")
                        && bodyNode.get("options").get("raw").has("language")
                        ? bodyNode.get("options").get("raw").get("language").asText() : "text";
                body.setRawType(lang);
            } else if ("urlencoded".equals(mode) || "formdata".equals(mode)) {
                String fieldKey = "formdata".equals(mode) ? "formdata" : "urlencoded";
                if (bodyNode.has(fieldKey)) {
                    for (JsonNode f : bodyNode.get(fieldKey)) {
                        FormItem item = new FormItem();
                        item.setKey(f.has("key") ? f.get("key").asText() : "");
                        item.setType("file".equals(f.has("type") ? f.get("type").asText() : "") ? "file" : "text");
                        if ("file".equals(item.getType())) {
                            item.setFilePath(f.has("src") ? f.get("src").asText() : "");
                        } else {
                            item.setValue(f.has("value") ? f.get("value").asText() : "");
                        }
                        item.setEnabled(!f.has("disabled") || !f.get("disabled").asBoolean());
                        body.getFormItems().add(item);
                    }
                }
            }
        }

        // Auth
        if (requestNode.has("auth")) {
            JsonNode authNode = requestNode.get("auth");
            String authType = authNode.has("type") ? authNode.get("type").asText() : "none";
            AuthConfig auth = request.getAuth();
            auth.setType(authType);

            if ("basic".equals(authType) && authNode.has("basic")) {
                for (JsonNode item : authNode.get("basic")) {
                    if ("username".equals(item.get("key").asText())) auth.setUsername(item.get("value").asText());
                    if ("password".equals(item.get("key").asText())) auth.setPassword(item.get("value").asText());
                }
            } else if ("bearer".equals(authType) && authNode.has("bearer")) {
                for (JsonNode item : authNode.get("bearer")) {
                    if ("token".equals(item.get("key").asText())) auth.setToken(item.get("value").asText());
                }
            } else if ("apikey".equals(authType) && authNode.has("apikey")) {
                for (JsonNode item : authNode.get("apikey")) {
                    String key = item.get("key").asText();
                    String value = item.get("value").asText();
                    switch (key) {
                        case "key" -> auth.setKey(value);
                        case "value" -> auth.setValue(value);
                        case "in" -> auth.setAddTo(value);
                    }
                }
            }
        }
    }

    // ==================== OpenAPI 3.0 导入 ====================

    /**
     * 导入 OpenAPI 3.0 JSON(按 tag 分组为多个 Collection)。
     *
     * @param jsonContent OpenAPI 3.0 JSON 字符串
     * @return 导入的 Collection 列表(已持久化)
     */
    public List<Collection> importOpenApi30(String jsonContent) throws Exception {
        JsonNode root = objectMapper.readTree(jsonContent);
        List<Collection> result = new ArrayList<>();

        String title = root.has("info") && root.get("info").has("title")
                ? root.get("info").get("title").asText() : "OpenAPI 导入";

        // 获取 baseUrl(effectively final,供 lambda 引用;无 servers 时为 null)
        final String baseUrl = (root.has("servers") && root.get("servers").size() > 0
                && root.get("servers").get(0).has("url"))
                ? root.get("servers").get(0).get("url").asText() : null;

        // 按 tag 分组
        java.util.Map<String, Collection> tagCollections = new java.util.LinkedHashMap<>();

        if (root.has("paths")) {
            JsonNode paths = root.get("paths");
            paths.fields().forEachRemaining(entry -> {
                String path = entry.getKey();
                JsonNode methods = entry.getValue();

                methods.fields().forEachRemaining(methodEntry -> {
                    String method = methodEntry.getKey().toUpperCase();
                    if (!isHttpMethod(method)) return;

                    JsonNode operation = methodEntry.getValue();
                    String tagName = operation.has("tags") && operation.get("tags").size() > 0
                            ? operation.get("tags").get(0).asText() : "默认";

                    // 获取或创建 tag 对应的 Collection
                    Collection collection = tagCollections.computeIfAbsent(tagName,
                            t -> {
                                Collection c = collectionService.createCollection(title + " - " + tagName);
                                c.setBaseUrl(baseUrl);
                                return c;
                            });

                    // 创建请求
                    String opName = operation.has("summary") ? operation.get("summary").asText()
                            : method + " " + path;
                    RequestNode request = new RequestNode(UUID.randomUUID().toString(), opName, method, path);

                    // Parameters (query + header)
                    if (operation.has("parameters")) {
                        for (JsonNode param : operation.get("parameters")) {
                            String in = param.has("in") ? param.get("in").asText() : "query";
                            String pName = param.has("name") ? param.get("name").asText() : "";
                            String pDesc = param.has("description") ? param.get("description").asText() : "";

                            if ("query".equals(in)) {
                                QueryParam qp = new QueryParam();
                                qp.setKey(pName);
                                qp.setDescription(pDesc);
                                request.getParams().add(qp);
                            } else if ("header".equals(in)) {
                                Header h = new Header();
                                h.setKey(pName);
                                h.setDescription(pDesc);
                                request.getHeaders().add(h);
                            }
                        }
                    }

                    // Request Body
                    if (operation.has("requestBody")) {
                        JsonNode rbNode = operation.get("requestBody");
                        if (rbNode.has("content")) {
                            JsonNode content = rbNode.get("content");
                            // 优先 JSON
                            String contentType = content.has("application/json") ? "application/json"
                                    : content.has("application/xml") ? "application/xml"
                                    : content.fields().hasNext() ? content.fields().next().getKey() : null;

                            if (contentType != null) {
                                request.getBody().setType("raw");
                                request.getBody().setRawType(contentType.contains("json") ? "json"
                                        : contentType.contains("xml") ? "xml" : "text");
                            }
                        }
                    }

                    collection.getItems().add(request);
                });
            });
        }

        // 保存所有集合并返回
        for (Collection c : tagCollections.values()) {
            collectionService.saveCollection(c);
            result.add(c);
        }

        log.info("OpenAPI 3.0 导入完成: {} 个集合", result.size());
        return result;
    }

    private boolean isHttpMethod(String method) {
        return method.matches("GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS");
    }

    // ==================== cURL 导入 ====================

    /** cURL 命令解析正则 */
    private static final Pattern CURL_URL_PATTERN = Pattern.compile(
            "(https?://[^'\"\\s]+)");
    private static final Pattern CURL_URL_FLAG_PATTERN = Pattern.compile(
            "--url\\s+['\"]?([^'\"\\s]+)");
    private static final Pattern CURL_HEADER_PATTERN = Pattern.compile(
            "(?:-H|--header)\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern CURL_METHOD_PATTERN = Pattern.compile(
            "(?:-X|--request)\\s+(\\w+)");
    private static final Pattern CURL_DATA_PATTERN = Pattern.compile(
            "(?:-d|--data|--data-raw)\\s+(['\"])([\\s\\S]+?)\\1");

    /**
     * 导入 cURL 命令为 RequestNode。
     *
     * @param curlCommand cURL 命令字符串
     * @return 解析出的 RequestNode
     */
    public RequestNode importCurl(String curlCommand) {
        // 清理命令:去除开头的 curl 和换行
        String cmd = curlCommand.trim();
        if (cmd.startsWith("curl")) {
            cmd = cmd.substring(4).trim();
        }
        // 合并多行
        cmd = cmd.replaceAll("\\\\\\s*\\n", " ");

        // 解析 Method
        String method = "GET";
        Matcher methodMatcher = CURL_METHOD_PATTERN.matcher(cmd);
        if (methodMatcher.find()) {
            method = methodMatcher.group(1).toUpperCase();
        }

        // 解析 URL: 优先匹配 http(s):// 开头,其次匹配 --url 标志
        String url = "";
        Matcher urlMatcher = CURL_URL_PATTERN.matcher(cmd);
        if (urlMatcher.find()) {
            url = urlMatcher.group(1);
        } else {
            Matcher urlFlagMatcher = CURL_URL_FLAG_PATTERN.matcher(cmd);
            if (urlFlagMatcher.find()) {
                url = urlFlagMatcher.group(1);
            }
        }

        // 如果有 -d 且没有指定 method,默认 POST
        Matcher dataMatcher = CURL_DATA_PATTERN.matcher(cmd);
        boolean hasData = dataMatcher.find();
        if (hasData && method.equals("GET")) {
            method = "POST";
        }

        RequestNode request = new RequestNode(UUID.randomUUID().toString(), "cURL 导入", method, url);

        // 解析 Headers
        Matcher headerMatcher = CURL_HEADER_PATTERN.matcher(cmd);
        while (headerMatcher.find()) {
            String headerStr = headerMatcher.group(1);
            int colonIdx = headerStr.indexOf(':');
            if (colonIdx > 0) {
                Header header = new Header();
                header.setKey(headerStr.substring(0, colonIdx).trim());
                header.setValue(headerStr.substring(colonIdx + 1).trim());
                request.getHeaders().add(header);

                // 如果有 Content-Type: application/json,设置 body rawType
                if (header.getKey().equalsIgnoreCase("Content-Type")
                        && header.getValue().contains("json")) {
                    request.getBody().setRawType("json");
                }
            }
        }

        // 解析 Body
        if (hasData) {
            String data = dataMatcher.group(2);
            RequestBody body = request.getBody();
            body.setType("raw");
            body.setContent(data);
            if (body.getRawType() == null || body.getRawType().isBlank()) {
                body.setRawType("text");
            }
        }

        log.info("cURL 导入完成: method={}, url={}", method, url);
        return request;
    }

    // ==================== Postman v2.1 导出 ====================

    /**
     * 将 Collection 导出为 Postman v2.1 JSON。
     *
     * @param collection 要导出的集合
     * @return Postman v2.1 格式的 JSON 字符串
     */
    public String exportPostmanV21(Collection collection) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode collectionNode = root.putObject("collection");

        // Info
        ObjectNode info = collectionNode.putObject("info");
        info.put("name", collection.getName());
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        info.put("_postman_id", collection.getId());

        // Items
        ArrayNode items = collectionNode.putArray("item");
        for (CollectionItem item : collection.getItems()) {
            items.add(exportItem(item));
        }

        // Variables
        if (!collection.getVariables().isEmpty()) {
            ArrayNode variables = collectionNode.putArray("variable");
            for (Variable var : collection.getVariables()) {
                ObjectNode varNode = variables.addObject();
                varNode.put("key", var.getKey());
                varNode.put("value", var.getValue());
                if (var.getDescription() != null) {
                    varNode.put("description", var.getDescription());
                }
            }
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** 递归导出 CollectionItem 为 Postman item 节点 */
    private ObjectNode exportItem(CollectionItem item) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", item.getName());

        if (item.isFolder()) {
            ArrayNode childItems = node.putArray("item");
            for (CollectionItem child : ((FolderNode) item).getItems()) {
                childItems.add(exportItem(child));
            }
        } else {
            RequestNode request = (RequestNode) item;
            ObjectNode requestNode = node.putObject("request");
            requestNode.put("method", request.getMethod());

            // URL
            ObjectNode urlNode = requestNode.putObject("url");
            urlNode.put("raw", request.getUrl());

            // Headers
            if (!request.getHeaders().isEmpty()) {
                ArrayNode headerArray = requestNode.putArray("header");
                for (Header h : request.getHeaders()) {
                    ObjectNode hNode = headerArray.addObject();
                    hNode.put("key", h.getKey());
                    hNode.put("value", h.getValue());
                    if (!h.isEnabled()) hNode.put("disabled", true);
                }
            }

            // Body
            RequestBody body = request.getBody();
            if (body != null && !"none".equals(body.getType())) {
                ObjectNode bodyNode = requestNode.putObject("body");
                String pmMode = switch (body.getType()) {
                    case "x-www-form-urlencoded" -> "urlencoded";
                    case "form-data" -> "formdata";
                    case "raw" -> "raw";
                    case "binary" -> "file";
                    default -> "raw";
                };
                bodyNode.put("mode", pmMode);

                if ("raw".equals(pmMode)) {
                    bodyNode.put("raw", body.getContent() != null ? body.getContent() : "");
                } else if ("urlencoded".equals(pmMode) || "formdata".equals(pmMode)) {
                    ArrayNode formArray = bodyNode.putArray(pmMode);
                    for (FormItem fi : body.getFormItems()) {
                        ObjectNode fNode = formArray.addObject();
                        fNode.put("key", fi.getKey());
                        if ("file".equals(fi.getType())) {
                            fNode.put("type", "file");
                            fNode.put("src", fi.getFilePath() != null ? fi.getFilePath() : "");
                        } else {
                            fNode.put("type", "text");
                            fNode.put("value", fi.getValue() != null ? fi.getValue() : "");
                        }
                        if (!fi.isEnabled()) fNode.put("disabled", true);
                    }
                }
            }
        }

        return node;
    }
}
