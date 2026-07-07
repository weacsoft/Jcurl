package com.jcurl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.config.AppConfig;
import com.jcurl.model.Collection;
import com.jcurl.model.FolderNode;
import com.jcurl.model.RequestNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImportExportService 集成测试 — 验证 Postman v2.1、OpenAPI 3.0、cURL 导入导出。
 */
@SpringBootTest(classes = AppConfig.class)
class ImportExportServiceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("jcurl.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private ImportExportService importExportService;

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private ObjectMapper objectMapper;

    private final java.util.List<String> createdCollectionIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : createdCollectionIds) {
            try {
                collectionService.deleteCollection(id);
            } catch (Exception ignored) {
            }
        }
        createdCollectionIds.clear();
    }

    /** 辅助:记录已创建的集合 ID 以便清理 */
    private Collection track(Collection c) {
        createdCollectionIds.add(c.getId());
        return c;
    }

    // ==================== Postman v2.1 导入 ====================

    @Nested
    @DisplayName("Postman v2.1 导入")
    class PostmanV21Import {

        @Test
        @DisplayName("导入简单 GET 请求集合")
        void importSimpleGetCollection() throws Exception {
            String json = """
                {
                  "info": {
                    "name": "测试集合",
                    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                  },
                  "item": [
                    {
                      "name": "获取用户列表",
                      "request": {
                        "method": "GET",
                        "url": {
                          "raw": "https://api.example.com/users",
                          "query": [
                            {"key": "page", "value": "1"},
                            {"key": "size", "value": "20", "disabled": true}
                          ]
                        }
                      }
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));

            assertEquals("测试集合", collection.getName());
            assertEquals(1, collection.getItems().size());

            RequestNode req = (RequestNode) collection.getItems().get(0);
            assertEquals("获取用户列表", req.getName());
            assertEquals("GET", req.getMethod());
            assertEquals("https://api.example.com/users", req.getUrl());
            assertEquals(2, req.getParams().size());
            assertEquals("page", req.getParams().get(0).getKey());
            assertEquals("1", req.getParams().get(0).getValue());
            assertTrue(req.getParams().get(0).isEnabled());
            assertEquals("size", req.getParams().get(1).getKey());
            assertFalse(req.getParams().get(1).isEnabled());
        }

        @Test
        @DisplayName("导入带文件夹嵌套的集合")
        void importNestedFolderCollection() throws Exception {
            String json = """
                {
                  "info": {"name": "嵌套集合"},
                  "item": [
                    {
                      "name": "用户管理",
                      "item": [
                        {
                          "name": "创建用户",
                          "request": {
                            "method": "POST",
                            "url": "https://api.example.com/users"
                          }
                        },
                        {
                          "name": "子文件夹",
                          "item": [
                            {
                              "name": "删除用户",
                              "request": {
                                "method": "DELETE",
                                "url": {"raw": "https://api.example.com/users/1"}
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));

            assertEquals(1, collection.getItems().size());
            FolderNode folder = (FolderNode) collection.getItems().get(0);
            assertEquals("用户管理", folder.getName());
            assertEquals(2, folder.getItems().size());

            // 第一个子项是请求
            RequestNode createReq = (RequestNode) folder.getItems().get(0);
            assertEquals("POST", createReq.getMethod());

            // 第二个子项是嵌套文件夹
            FolderNode subFolder = (FolderNode) folder.getItems().get(1);
            assertEquals("子文件夹", subFolder.getName());
            assertEquals(1, subFolder.getItems().size());

            RequestNode deleteReq = (RequestNode) subFolder.getItems().get(0);
            assertEquals("DELETE", deleteReq.getMethod());
        }

        @Test
        @DisplayName("导入带 raw JSON body 的请求")
        void importRawJsonBody() throws Exception {
            String json = """
                {
                  "info": {"name": "Body 集合"},
                  "item": [
                    {
                      "name": "POST JSON",
                      "request": {
                        "method": "POST",
                        "url": "https://api.example.com/data",
                        "body": {
                          "mode": "raw",
                          "raw": "{\\"key\\": \\"value\\"}",
                          "options": {"raw": {"language": "json"}}
                        }
                      }
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));
            RequestNode req = (RequestNode) collection.getItems().get(0);

            assertEquals("raw", req.getBody().getType());
            assertEquals("{\"key\": \"value\"}", req.getBody().getContent());
            assertEquals("json", req.getBody().getRawType());
        }

        @Test
        @DisplayName("导入带 form-data body 的请求")
        void importFormDataBody() throws Exception {
            String json = """
                {
                  "info": {"name": "Form 集合"},
                  "item": [
                    {
                      "name": "上传文件",
                      "request": {
                        "method": "POST",
                        "url": "https://api.example.com/upload",
                        "body": {
                          "mode": "formdata",
                          "formdata": [
                            {"key": "name", "value": "test", "type": "text"},
                            {"key": "file", "type": "file", "src": "/tmp/test.txt"}
                          ]
                        }
                      }
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));
            RequestNode req = (RequestNode) collection.getItems().get(0);

            assertEquals("form-data", req.getBody().getType());
            assertEquals(2, req.getBody().getFormItems().size());
            assertEquals("name", req.getBody().getFormItems().get(0).getKey());
            assertEquals("text", req.getBody().getFormItems().get(0).getType());
            assertEquals("test", req.getBody().getFormItems().get(0).getValue());
            assertEquals("file", req.getBody().getFormItems().get(1).getType());
            assertEquals("/tmp/test.txt", req.getBody().getFormItems().get(1).getFilePath());
        }

        @Test
        @DisplayName("导入带 Bearer Token 认证的请求")
        void importBearerAuth() throws Exception {
            String json = """
                {
                  "info": {"name": "Auth 集合"},
                  "item": [
                    {
                      "name": "Bearer 请求",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.com/secure",
                        "auth": {
                          "type": "bearer",
                          "bearer": [
                            {"key": "token", "value": "my-secret-token"}
                          ]
                        }
                      }
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));
            RequestNode req = (RequestNode) collection.getItems().get(0);

            assertEquals("bearer", req.getAuth().getType());
            assertEquals("my-secret-token", req.getAuth().getToken());
        }

        @Test
        @DisplayName("导入带 Basic Auth 的请求")
        void importBasicAuth() throws Exception {
            String json = """
                {
                  "info": {"name": "Basic Auth 集合"},
                  "item": [
                    {
                      "name": "Basic 请求",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.com/basic",
                        "auth": {
                          "type": "basic",
                          "basic": [
                            {"key": "username", "value": "admin"},
                            {"key": "password", "value": "pass123"}
                          ]
                        }
                      }
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));
            RequestNode req = (RequestNode) collection.getItems().get(0);

            assertEquals("basic", req.getAuth().getType());
            assertEquals("admin", req.getAuth().getUsername());
            assertEquals("pass123", req.getAuth().getPassword());
        }

        @Test
        @DisplayName("导入带集合变量的集合")
        void importCollectionVariables() throws Exception {
            String json = """
                {
                  "info": {"name": "变量集合"},
                  "variable": [
                    {"key": "baseUrl", "value": "https://api.example.com"},
                    {"key": "token", "value": "abc123"}
                  ],
                  "item": []
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));

            assertEquals(2, collection.getVariables().size());
            assertEquals("baseUrl", collection.getVariables().get(0).getKey());
            assertEquals("https://api.example.com", collection.getVariables().get(0).getValue());
        }

        @Test
        @DisplayName("导入带包装格式的集合(collection 外层包装)")
        void importWrappedCollection() throws Exception {
            String json = """
                {
                  "collection": {
                    "info": {"name": "包装集合"},
                    "item": [
                      {
                        "name": "请求1",
                        "request": {"method": "GET", "url": "https://example.com"}
                      }
                    ]
                  }
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));
            assertEquals("包装集合", collection.getName());
            assertEquals(1, collection.getItems().size());
        }

        @Test
        @DisplayName("导入带 Headers 的请求")
        void importHeaders() throws Exception {
            String json = """
                {
                  "info": {"name": "Headers 集合"},
                  "item": [
                    {
                      "name": "带 Headers",
                      "request": {
                        "method": "GET",
                        "url": "https://api.example.com",
                        "header": [
                          {"key": "Accept", "value": "application/json"},
                          {"key": "X-Custom", "value": "test", "disabled": true}
                        ]
                      }
                    }
                  ]
                }
                """;

            Collection collection = track(importExportService.importPostmanV21(json));
            RequestNode req = (RequestNode) collection.getItems().get(0);

            assertEquals(2, req.getHeaders().size());
            assertEquals("Accept", req.getHeaders().get(0).getKey());
            assertTrue(req.getHeaders().get(0).isEnabled());
            assertEquals("X-Custom", req.getHeaders().get(1).getKey());
            assertFalse(req.getHeaders().get(1).isEnabled());
        }
    }

    // ==================== OpenAPI 3.0 导入 ====================

    @Nested
    @DisplayName("OpenAPI 3.0 导入")
    class OpenApi30Import {

        @Test
        @DisplayName("导入基本 OpenAPI 3.0 文档(按 tag 分组)")
        void importBasicOpenApi() throws Exception {
            String json = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "示例 API", "version": "1.0.0"},
                  "servers": [{"url": "https://api.example.com/v1"}],
                  "paths": {
                    "/users": {
                      "get": {
                        "summary": "获取用户列表",
                        "tags": ["用户"],
                        "parameters": [
                          {"name": "page", "in": "query", "description": "页码"}
                        ]
                      },
                      "post": {
                        "summary": "创建用户",
                        "tags": ["用户"],
                        "requestBody": {
                          "content": {"application/json": {}}
                        }
                      }
                    },
                    "/orders": {
                      "get": {
                        "summary": "获取订单",
                        "tags": ["订单"]
                      }
                    }
                  }
                }
                """;

            List<Collection> collections = importExportService.importOpenApi30(json);
            for (Collection c : collections) {
                track(c);
            }

            // 应按 tag 分成 2 个集合
            assertEquals(2, collections.size());

            // 找到"用户"集合
            Collection userCol = collections.stream()
                    .filter(c -> c.getName().contains("用户"))
                    .findFirst().orElseThrow();
            assertEquals("https://api.example.com/v1", userCol.getBaseUrl());
            assertEquals(2, userCol.getItems().size());

            RequestNode getReq = (RequestNode) userCol.getItems().get(0);
            assertEquals("GET", getReq.getMethod());
            assertEquals("/users", getReq.getUrl());
            assertEquals("获取用户列表", getReq.getName());
            assertEquals(1, getReq.getParams().size());
            assertEquals("page", getReq.getParams().get(0).getKey());

            RequestNode postReq = (RequestNode) userCol.getItems().get(1);
            assertEquals("POST", postReq.getMethod());
            assertEquals("raw", postReq.getBody().getType());
            assertEquals("json", postReq.getBody().getRawType());

            // 找到"订单"集合
            Collection orderCol = collections.stream()
                    .filter(c -> c.getName().contains("订单"))
                    .findFirst().orElseThrow();
            assertEquals(1, orderCol.getItems().size());
        }

        @Test
        @DisplayName("导入无 tag 的 OpenAPI 文档(归入默认)")
        void importNoTagOpenApi() throws Exception {
            String json = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "无 Tag API", "version": "1.0.0"},
                  "paths": {
                    "/health": {
                      "get": {
                        "summary": "健康检查"
                      }
                    }
                  }
                }
                """;

            List<Collection> collections = importExportService.importOpenApi30(json);
            for (Collection c : collections) {
                track(c);
            }

            assertEquals(1, collections.size());
            assertTrue(collections.get(0).getName().contains("默认"));
            assertEquals(1, collections.get(0).getItems().size());
        }

        @Test
        @DisplayName("导入带 Header 参数的 OpenAPI 文档")
        void importHeaderParams() throws Exception {
            String json = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Header API", "version": "1.0.0"},
                  "paths": {
                    "/data": {
                      "get": {
                        "summary": "获取数据",
                        "tags": ["数据"],
                        "parameters": [
                          {"name": "X-API-Key", "in": "header", "description": "API密钥"}
                        ]
                      }
                    }
                  }
                }
                """;

            List<Collection> collections = importExportService.importOpenApi30(json);
            for (Collection c : collections) {
                track(c);
            }

            RequestNode req = (RequestNode) collections.get(0).getItems().get(0);
            assertEquals(1, req.getHeaders().size());
            assertEquals("X-API-Key", req.getHeaders().get(0).getKey());
        }

        @Test
        @DisplayName("导入无 servers 的 OpenAPI 文档")
        void importNoServers() throws Exception {
            String json = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "No Server API", "version": "1.0.0"},
                  "paths": {
                    "/test": {
                      "get": {"summary": "测试", "tags": ["Test"]}
                    }
                  }
                }
                """;

            List<Collection> collections = importExportService.importOpenApi30(json);
            for (Collection c : collections) {
                track(c);
            }

            assertNull(collections.get(0).getBaseUrl());
        }
    }

    // ==================== cURL 导入 ====================

    @Nested
    @DisplayName("cURL 导入")
    class CurlImport {

        @Test
        @DisplayName("导入简单 GET cURL 命令")
        void importSimpleGetCurl() {
            String curl = "curl https://api.example.com/users";

            RequestNode request = importExportService.importCurl(curl);

            assertEquals("GET", request.getMethod());
            assertEquals("https://api.example.com/users", request.getUrl());
        }

        @Test
        @DisplayName("导入带 -X 指定方法的 cURL")
        void importWithMethodFlag() {
            String curl = "curl -X PUT https://api.example.com/users/1";

            RequestNode request = importExportService.importCurl(curl);

            assertEquals("PUT", request.getMethod());
            assertEquals("https://api.example.com/users/1", request.getUrl());
        }

        @Test
        @DisplayName("导入带 Headers 的 cURL")
        void importWithHeaders() {
            String curl = "curl -H 'Content-Type: application/json' -H 'Authorization: Bearer token123' https://api.example.com/data";

            RequestNode request = importExportService.importCurl(curl);

            assertEquals(2, request.getHeaders().size());
            assertEquals("Content-Type", request.getHeaders().get(0).getKey());
            assertEquals("application/json", request.getHeaders().get(0).getValue());
            assertEquals("Authorization", request.getHeaders().get(1).getKey());
            assertEquals("Bearer token123", request.getHeaders().get(1).getValue());
        }

        @Test
        @DisplayName("导入带 -d 数据的 POST cURL(自动推断 POST)")
        void importPostWithData() {
            String curl = "curl -H 'Content-Type: application/json' -d '{\"name\":\"test\"}' https://api.example.com/users";

            RequestNode request = importExportService.importCurl(curl);

            // 有 -d 且未指定 method → 自动推断 POST
            assertEquals("POST", request.getMethod());
            assertEquals("raw", request.getBody().getType());
            assertEquals("{\"name\":\"test\"}", request.getBody().getContent());
            assertEquals("json", request.getBody().getRawType());
        }

        @Test
        @DisplayName("导入带 --data-raw 的 cURL")
        void importWithRawData() {
            String curl = "curl --data-raw 'plain text body' https://api.example.com/submit";

            RequestNode request = importExportService.importCurl(curl);

            assertEquals("POST", request.getMethod());
            assertEquals("plain text body", request.getBody().getContent());
        }

        @Test
        @DisplayName("导入多行 cURL 命令(续行符)")
        void importMultilineCurl() {
            String curl = "curl -X POST \\\n  -H 'Content-Type: application/json' \\\n  -d '{\"key\":\"value\"}' \\\n  https://api.example.com/multi";

            RequestNode request = importExportService.importCurl(curl);

            assertEquals("POST", request.getMethod());
            assertEquals("https://api.example.com/multi", request.getUrl());
            assertEquals(1, request.getHeaders().size());
            assertEquals("{\"key\":\"value\"}", request.getBody().getContent());
        }

        @Test
        @DisplayName("导入无 URL 的 cURL(返回空 URL)")
        void importNoUrlCurl() {
            String curl = "curl -X GET";

            RequestNode request = importExportService.importCurl(curl);

            assertEquals("GET", request.getMethod());
            assertTrue(request.getUrl().isEmpty());
        }
    }

    // ==================== Postman v2.1 导出 ====================

    @Nested
    @DisplayName("Postman v2.1 导出")
    class PostmanV21Export {

        @Test
        @DisplayName("导出简单集合并验证结构")
        void exportSimpleCollection() throws Exception {
            Collection collection = track(collectionService.createCollection("导出测试"));
            collectionService.addRequest(collection, null, "GET 请求", "GET", "https://api.example.com/test");

            String json = importExportService.exportPostmanV21(collection);
            JsonNode root = objectMapper.readTree(json);
            JsonNode colNode = root.get("collection");

            assertEquals("导出测试", colNode.get("info").get("name").asText());
            assertTrue(colNode.get("item").size() > 0);

            JsonNode item = colNode.get("item").get(0);
            assertEquals("GET 请求", item.get("name").asText());
            assertEquals("GET", item.get("request").get("method").asText());
            assertEquals("https://api.example.com/test", item.get("request").get("url").get("raw").asText());
        }

        @Test
        @DisplayName("导出带嵌套文件夹的集合")
        void exportNestedFolders() throws Exception {
            Collection collection = track(collectionService.createCollection("嵌套导出"));
            FolderNode folder = collectionService.addFolder(collection, null, "文件夹1");
            collectionService.addRequest(collection, folder.getId(), "子请求", "POST", "https://api.example.com/child");

            String json = importExportService.exportPostmanV21(collection);
            JsonNode root = objectMapper.readTree(json);

            JsonNode item = root.get("collection").get("item").get(0);
            assertEquals("文件夹1", item.get("name").asText());
            assertTrue(item.has("item"));
            assertEquals("子请求", item.get("item").get(0).get("name").asText());
        }

        @Test
        @DisplayName("导出带 Headers 的请求")
        void exportWithHeaders() throws Exception {
            Collection collection = track(collectionService.createCollection("Header 导出"));
            collectionService.addRequest(collection, null, "请求", "GET", "https://api.example.com");

            // 添加 header
            RequestNode req = (RequestNode) collection.getItems().get(0);
            req.getHeaders().add(new com.jcurl.plugin.model.component.Header("Accept", "application/json"));
            collectionService.saveCollection(collection);

            String json = importExportService.exportPostmanV21(collection);
            JsonNode root = objectMapper.readTree(json);

            JsonNode headers = root.get("collection").get("item").get(0).get("request").get("header");
            assertEquals(1, headers.size());
            assertEquals("Accept", headers.get(0).get("key").asText());
            assertEquals("application/json", headers.get(0).get("value").asText());
        }

        @Test
        @DisplayName("导出带变量的集合")
        void exportWithVariables() throws Exception {
            Collection collection = track(collectionService.createCollection("变量导出"));
            collection.getVariables().add(new com.jcurl.plugin.model.component.Variable("baseUrl", "https://api.example.com"));
            collectionService.saveCollection(collection);

            String json = importExportService.exportPostmanV21(collection);
            JsonNode root = objectMapper.readTree(json);

            JsonNode variables = root.get("collection").get("variable");
            assertEquals(1, variables.size());
            assertEquals("baseUrl", variables.get(0).get("key").asText());
            assertEquals("https://api.example.com", variables.get(0).get("value").asText());
        }

        @Test
        @DisplayName("导出带 raw body 的请求")
        void exportRawBody() throws Exception {
            Collection collection = track(collectionService.createCollection("Body 导出"));
            collectionService.addRequest(collection, null, "POST 请求", "POST", "https://api.example.com");

            RequestNode req = (RequestNode) collection.getItems().get(0);
            req.getBody().setType("raw");
            req.getBody().setContent("{\"key\":\"value\"}");
            req.getBody().setRawType("json");
            collectionService.saveCollection(collection);

            String json = importExportService.exportPostmanV21(collection);
            JsonNode root = objectMapper.readTree(json);

            JsonNode body = root.get("collection").get("item").get(0).get("request").get("body");
            assertEquals("raw", body.get("mode").asText());
            assertEquals("{\"key\":\"value\"}", body.get("raw").asText());
        }
    }

    // ==================== 往返测试(导入→导出→导入) ====================

    @Nested
    @DisplayName("往返测试")
    class RoundTripTest {

        @Test
        @DisplayName("Postman v2.1 导出后再导入,数据一致")
        void exportThenImport() throws Exception {
            // 创建带完整结构的集合
            Collection original = track(collectionService.createCollection("往返测试"));
            collectionService.addRequest(original, null, "GET 请求", "GET", "https://api.example.com/get");
            collectionService.addRequest(original, null, "POST 请求", "POST", "https://api.example.com/post");

            // 导出
            String exportedJson = importExportService.exportPostmanV21(original);

            // 导入
            Collection reimported = track(importExportService.importPostmanV21(exportedJson));

            assertEquals("往返测试", reimported.getName());
            assertEquals(2, reimported.getItems().size());

            RequestNode getReq = (RequestNode) reimported.getItems().get(0);
            assertEquals("GET 请求", getReq.getName());
            assertEquals("GET", getReq.getMethod());
            assertEquals("https://api.example.com/get", getReq.getUrl());

            RequestNode postReq = (RequestNode) reimported.getItems().get(1);
            assertEquals("POST", postReq.getMethod());
        }
    }
}
