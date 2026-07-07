package com.jcurl.service;

import com.jcurl.model.Collection;
import com.jcurl.model.RequestNode;
import com.jcurl.plugin.model.component.AuthConfig;
import com.jcurl.plugin.model.component.Header;
import com.jcurl.plugin.model.component.QueryParam;
import com.jcurl.plugin.model.component.RequestBody;
import com.jcurl.plugin.model.dto.RequestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InheritanceMerger 单元测试 — 验证集合继承合并逻辑。
 * <p>
 * 纯逻辑测试,不依赖 Spring 容器。
 */
class InheritanceMergerTest {

    private InheritanceMerger merger;

    @BeforeEach
    void setUp() {
        merger = new InheritanceMerger();
    }

    // ==================== URL 合并 ====================

    @Nested
    @DisplayName("URL 合并")
    class UrlMergeTest {

        @Test
        @DisplayName("绝对 URL 直接使用,忽略 baseUrl")
        void absoluteUrlShouldBeUsedDirectly() {
            String result = merger.mergeUrl("https://api.example.com/users", "https://other.com/v1");
            assertEquals("https://api.example.com/users", result);
        }

        @Test
        @DisplayName("相对路径拼接 baseUrl")
        void relativePathShouldBePrependedWithBaseUrl() {
            assertEquals("https://api.example.com/v1/users",
                    merger.mergeUrl("/users", "https://api.example.com/v1"));
            assertEquals("https://api.example.com/v1/users",
                    merger.mergeUrl("users", "https://api.example.com/v1"));
        }

        @Test
        @DisplayName("baseUrl 尾部斜杠与相对路径前导斜杠不重复")
        void shouldHandleTrailingAndLeadingSlashes() {
            assertEquals("https://api.example.com/v1/users",
                    merger.mergeUrl("/users", "https://api.example.com/v1/"));
            assertEquals("https://api.example.com/v1/users",
                    merger.mergeUrl("users", "https://api.example.com/v1/"));
        }

        @Test
        @DisplayName("baseUrl 为空时直接使用请求 URL")
        void emptyBaseUrlShouldUseRequestUrl() {
            assertEquals("/users", merger.mergeUrl("/users", null));
            assertEquals("/users", merger.mergeUrl("/users", ""));
        }

        @Test
        @DisplayName("请求 URL 为空时返回 baseUrl")
        void emptyRequestUrlShouldReturnBaseUrl() {
            assertEquals("https://api.example.com", merger.mergeUrl("", "https://api.example.com"));
            assertEquals("https://api.example.com", merger.mergeUrl(null, "https://api.example.com"));
        }
    }

    // ==================== Headers 合并 ====================

    @Nested
    @DisplayName("Headers 合并")
    class HeaderMergeTest {

        @Test
        @DisplayName("集合级与请求级不同 Key 合并")
        void shouldMergeDifferentKeys() {
            List<Header> collectionHeaders = List.of(new Header("X-Trace-Id", "abc123"));
            List<Header> requestHeaders = List.of(new Header("Accept", "application/json"));

            List<Header> result = merger.mergeHeaders(collectionHeaders, requestHeaders);

            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(h -> h.getKey().equals("X-Trace-Id")));
            assertTrue(result.stream().anyMatch(h -> h.getKey().equals("Accept")));
        }

        @Test
        @DisplayName("请求级同名 Header 覆盖集合级")
        void requestHeaderShouldOverrideCollectionHeader() {
            List<Header> collectionHeaders = List.of(new Header("Content-Type", "application/xml"));
            List<Header> requestHeaders = List.of(new Header("Content-Type", "application/json"));

            List<Header> result = merger.mergeHeaders(collectionHeaders, requestHeaders);

            assertEquals(1, result.size());
            assertEquals("application/json", result.get(0).getValue());
        }

        @Test
        @DisplayName("Header Key 大小写不敏感去重")
        void headerKeyCaseInsensitive() {
            List<Header> collectionHeaders = List.of(new Header("Content-Type", "application/xml"));
            List<Header> requestHeaders = List.of(new Header("content-type", "application/json"));

            List<Header> result = merger.mergeHeaders(collectionHeaders, requestHeaders);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("集合级为空时只使用请求级")
        void emptyCollectionHeaders() {
            List<Header> requestHeaders = List.of(new Header("Accept", "application/json"));
            List<Header> result = merger.mergeHeaders(null, requestHeaders);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("请求级为空时只使用集合级")
        void emptyRequestHeaders() {
            List<Header> collectionHeaders = List.of(new Header("X-Trace-Id", "abc123"));
            List<Header> result = merger.mergeHeaders(collectionHeaders, null);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("合并结果为深拷贝,修改不影响原始对象")
        void resultShouldBeDeepCopy() {
            Header collectionHeader = new Header("X-Custom", "original");
            List<Header> collectionHeaders = List.of(collectionHeader);

            List<Header> result = merger.mergeHeaders(collectionHeaders, null);
            result.get(0).setValue("modified");

            assertNotEquals("modified", collectionHeader.getValue());
        }
    }

    // ==================== Auth 合并 ====================

    @Nested
    @DisplayName("Auth 合并")
    class AuthMergeTest {

        @Test
        @DisplayName("请求 inherit 时继承集合级 Bearer")
        void shouldInheritCollectionBearer() {
            AuthConfig requestAuth = AuthConfig.inherit();
            AuthConfig collectionAuth = new AuthConfig("bearer");
            collectionAuth.setToken("my-token");

            AuthConfig result = merger.mergeAuth(requestAuth, collectionAuth);

            assertEquals("bearer", result.getType());
            assertEquals("my-token", result.getToken());
        }

        @Test
        @DisplayName("请求 inherit + 集合级也为 inherit → none")
        void inheritWithInheritCollectionShouldBeNone() {
            AuthConfig requestAuth = AuthConfig.inherit();
            AuthConfig collectionAuth = AuthConfig.inherit();

            AuthConfig result = merger.mergeAuth(requestAuth, collectionAuth);
            assertEquals("none", result.getType());
        }

        @Test
        @DisplayName("请求 inherit + 集合级为 null → none")
        void inheritWithNullCollectionShouldBeNone() {
            AuthConfig result = merger.mergeAuth(AuthConfig.inherit(), null);
            assertEquals("none", result.getType());
        }

        @Test
        @DisplayName("请求自身 Basic 认证不被集合级覆盖")
        void requestOwnAuthShouldNotBeOverridden() {
            AuthConfig requestAuth = new AuthConfig("basic");
            requestAuth.setUsername("user");
            requestAuth.setPassword("pass");
            AuthConfig collectionAuth = new AuthConfig("bearer");
            collectionAuth.setToken("collection-token");

            AuthConfig result = merger.mergeAuth(requestAuth, collectionAuth);

            assertEquals("basic", result.getType());
            assertEquals("user", result.getUsername());
            assertEquals("pass", result.getPassword());
        }

        @Test
        @DisplayName("合并结果为深拷贝")
        void authResultShouldBeDeepCopy() {
            AuthConfig collectionAuth = new AuthConfig("bearer");
            collectionAuth.setToken("original");

            AuthConfig result = merger.mergeAuth(AuthConfig.inherit(), collectionAuth);
            result.setToken("modified");

            assertEquals("original", collectionAuth.getToken());
        }
    }

    // ==================== 完整 merge ====================

    @Nested
    @DisplayName("完整 merge")
    class FullMergeTest {

        @Test
        @DisplayName("完整合并: URL + Headers + Auth + Params + Body")
        void fullMerge() {
            // 集合
            Collection collection = new Collection("col-1", "测试集合");
            collection.setBaseUrl("https://api.example.com/v1");
            collection.getHeaders().add(new Header("X-Trace-Id", "trace-001"));
            AuthConfig collectionAuth = new AuthConfig("bearer");
            collectionAuth.setToken("col-token");
            collection.setAuth(collectionAuth);

            // 请求
            RequestNode request = new RequestNode("req-1", "获取用户", "GET", "/users");
            request.getHeaders().add(new Header("Accept", "application/json"));
            request.setAuth(AuthConfig.inherit());
            request.getParams().add(new QueryParam("page", "1"));

            RequestConfig config = merger.merge(request, collection);

            // URL 合并
            assertEquals("https://api.example.com/v1/users", config.getUrl());
            // Method
            assertEquals("GET", config.getMethod());
            // Headers: 集合级 X-Trace-Id + 请求级 Accept
            assertEquals(2, config.getHeaders().size());
            // Auth: 继承集合级 Bearer
            assertEquals("bearer", config.getAuth().getType());
            assertEquals("col-token", config.getAuth().getToken());
            // Params 直接复制
            assertEquals(1, config.getParams().size());
            assertEquals("page", config.getParams().get(0).getKey());
        }

        @Test
        @DisplayName("请求自身 Header 覆盖集合级同名 Header")
        void requestHeaderOverridesCollection() {
            Collection collection = new Collection("col-1", "测试集合");
            collection.getHeaders().add(new Header("Accept", "application/xml"));

            RequestNode request = new RequestNode("req-1", "获取用户", "GET", "/users");
            request.getHeaders().add(new Header("Accept", "application/json"));

            RequestConfig config = merger.merge(request, collection);

            assertEquals(1, config.getHeaders().size());
            assertEquals("application/json", config.getHeaders().get(0).getValue());
        }

        @Test
        @DisplayName("Body 被深拷贝到 RequestConfig")
        void bodyShouldBeCopied() {
            Collection collection = new Collection("col-1", "测试集合");
            RequestNode request = new RequestNode("req-1", "创建用户", "POST", "/users");
            request.getBody().setType("raw");
            request.getBody().setRawType("json");
            request.getBody().setContent("{\"name\":\"test\"}");

            RequestConfig config = merger.merge(request, collection);

            assertEquals("raw", config.getBody().getType());
            assertEquals("json", config.getBody().getRawType());
            assertEquals("{\"name\":\"test\"}", config.getBody().getContent());

            // 修改 config 不影响原 request
            config.getBody().setContent("modified");
            assertNotEquals("modified", request.getBody().getContent());
        }
    }

    // ==================== mergeAll ====================

    @Nested
    @DisplayName("批量合并 mergeAll")
    class MergeAllTest {

        @Test
        @DisplayName("批量合并集合中所有请求")
        void shouldMergeAllRequests() {
            Collection collection = new Collection("col-1", "测试集合");
            collection.setBaseUrl("https://api.example.com");

            // 根级别请求
            RequestNode req1 = new RequestNode("req-1", "获取用户列表", "GET", "/users");
            collection.getItems().add(req1);

            // Folder 下嵌套请求
            com.jcurl.model.FolderNode folder = new com.jcurl.model.FolderNode("f-1", "订单");
            RequestNode req2 = new RequestNode("req-2", "创建订单", "POST", "/orders");
            folder.getItems().add(req2);
            collection.getItems().add(folder);

            List<RequestConfig> result = merger.mergeAll(collection);

            assertEquals(2, result.size());
            assertEquals("https://api.example.com/users", result.get(0).getUrl());
            assertEquals("https://api.example.com/orders", result.get(1).getUrl());
        }
    }
}
