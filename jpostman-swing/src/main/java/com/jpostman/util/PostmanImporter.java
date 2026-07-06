package com.jpostman.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman.model.AuthConfig;
import com.jpostman.model.CollectionFile;
import com.jpostman.model.CollectionItem;
import com.jpostman.model.FolderNode;
import com.jpostman.model.KeyValue;
import com.jpostman.model.RequestNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Postman Collection v2.1 导入器。
 * 将 Postman 导出的 JSON 文件转换为 CollectionFile。
 */
public class PostmanImporter {

    /**
     * 导入 Postman Collection v2.1 JSON。
     * @param jsonText Postman 导出的 JSON 文本
     * @param objectMapper Jackson ObjectMapper
     * @return 导入的 CollectionFile
     */
    public static CollectionFile importCollection(String jsonText, ObjectMapper objectMapper) throws Exception {
        JsonNode root = objectMapper.readTree(jsonText);
        JsonNode info = root.path("info");

        CollectionFile collection = new CollectionFile();
        collection.setName(info.path("name").asText("导入的集合"));
        collection.setDescription(info.path("description").asText(null));

        JsonNode itemArray = root.path("item");
        if (itemArray.isArray()) {
            for (JsonNode item : itemArray) {
                collection.getItems().add(parseItem(item));
            }
        }
        return collection;
    }

    private static CollectionItem parseItem(JsonNode node) {
        boolean hasRequest = node.has("request");
        if (hasRequest) {
            return parseRequest(node);
        } else {
            return parseFolder(node);
        }
    }

    private static FolderNode parseFolder(JsonNode node) {
        FolderNode folder = new FolderNode(node.path("name").asText("文件夹"));
        JsonNode items = node.path("item");
        if (items.isArray()) {
            for (JsonNode item : items) {
                folder.addItem(parseItem(item));
            }
        }
        return folder;
    }

    private static RequestNode parseRequest(JsonNode node) {
        RequestNode request = new RequestNode(node.path("name").asText("请求"));
        JsonNode reqNode = node.path("request");

        // method
        request.setMethod(reqNode.path("method").asText("GET"));

        // url
        JsonNode urlNode = reqNode.path("url");
        if (urlNode.isTextual()) {
            request.setUrl(urlNode.asText());
        } else if (urlNode.isObject()) {
            request.setUrl(urlNode.path("raw").asText(null));
            // 解析 query params
            JsonNode queryArray = urlNode.path("query");
            if (queryArray.isArray()) {
                List<KeyValue> params = new ArrayList<>();
                for (JsonNode q : queryArray) {
                    params.add(new KeyValue(
                        q.path("key").asText(""),
                        q.path("value").asText(""),
                        q.path("description").asText(""),
                        !q.path("disabled").asBoolean(false)
                    ));
                }
                request.setParams(params);
            }
        }

        // headers
        JsonNode headerArray = reqNode.path("header");
        if (headerArray.isArray()) {
            List<KeyValue> headers = new ArrayList<>();
            for (JsonNode h : headerArray) {
                headers.add(new KeyValue(
                    h.path("key").asText(""),
                    h.path("value").asText(""),
                    h.path("description").asText(""),
                    !h.path("disabled").asBoolean(false)
                ));
            }
            request.setHeaders(headers);
        }

        // body
        JsonNode bodyNode = reqNode.path("body");
        if (!bodyNode.isMissingNode() && bodyNode.path("mode").asText(null) != null) {
            String mode = bodyNode.path("mode").asText();
            if ("raw".equals(mode)) {
                request.setBodyType("raw");
                request.setBodyContent(bodyNode.path("raw").asText(""));
                JsonNode options = bodyNode.path("options");
                if (options.has("raw")) {
                    String lang = options.path("raw").path("language").asText("text");
                    switch (lang) {
                        case "json": request.setRawContentType("application/json"); break;
                        case "xml": request.setRawContentType("application/xml"); break;
                        case "html": request.setRawContentType("text/html"); break;
                        default: request.setRawContentType("text/plain");
                    }
                }
            } else if ("urlencoded".equals(mode)) {
                request.setBodyType("urlencoded");
                List<KeyValue> kvs = new ArrayList<>();
                JsonNode dataArray = bodyNode.path("urlencoded");
                if (dataArray.isArray()) {
                    for (JsonNode d : dataArray) {
                        kvs.add(new KeyValue(d.path("key").asText(""), d.path("value").asText("")));
                    }
                }
                try {
                    request.setBodyContent(new ObjectMapper().writeValueAsString(kvs));
                } catch (Exception e) {
                    request.setBodyContent("[]");
                }
            } else if ("formdata".equals(mode)) {
                request.setBodyType("form-data");
                // 类似 urlencoded
                List<KeyValue> kvs = new ArrayList<>();
                JsonNode dataArray = bodyNode.path("formdata");
                if (dataArray.isArray()) {
                    for (JsonNode d : dataArray) {
                        kvs.add(new KeyValue(d.path("key").asText(""), d.path("value").asText("")));
                    }
                }
                try {
                    request.setBodyContent(new ObjectMapper().writeValueAsString(kvs));
                } catch (Exception e) {
                    request.setBodyContent("[]");
                }
            }
        }

        // auth
        JsonNode authNode = reqNode.path("auth");
        if (!authNode.isMissingNode()) {
            String authType = authNode.path("type").asText(null);
            if (authType != null) {
                AuthConfig auth = new AuthConfig();
                auth.setType(authType);
                JsonNode typeNode = authNode.path(authType);
                if (typeNode.isArray()) {
                    for (JsonNode pair : typeNode) {
                        String key = pair.path("key").asText();
                        String value = pair.path("value").asText();
                        if ("username".equals(key)) auth.setBasicUsername(value);
                        else if ("password".equals(key)) auth.setBasicPassword(value);
                        else if ("token".equals(key)) auth.setBearerToken(value);
                        else if ("key".equals(key)) auth.setApiKeyName(value);
                        else if ("value".equals(key)) auth.setApiKeyValue(value);
                    }
                }
                request.setAuth(auth);
            }
        }

        return request;
    }
}
