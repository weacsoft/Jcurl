package com.jcurl2.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl2.config.AppConfig;
import com.jcurl2.model.component.AuthConfig;
import com.jcurl2.model.component.Header;
import com.jcurl2.model.component.QueryParam;
import com.jcurl2.model.component.RequestBody;
import com.jcurl2.store.JsonStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Collection 树多态序列化测试 — 验证 Folder 无限嵌套与 Request 节点的 JSON 读写。
 */
@SpringBootTest(classes = AppConfig.class)
class CollectionSerializationTest {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JsonStoreService store;

    @Test
    void shouldSerializeAndDeserializeNestedTree() throws Exception {
        // 构建多层级集合树: Collection → Folder → Folder → Request
        Collection col = new Collection("col_test", "测试集合");
        col.setBaseUrl("https://api.example.com/v1");
        col.getHeaders().add(new Header("X-Service", "test"));
        col.getVariables().add(new com.jcurl2.model.component.Variable("token", "abc123"));

        // 一级 Folder
        FolderNode folder1 = new FolderNode("fld_1", "用户模块");
        col.getItems().add(folder1);

        // 二级 Folder(嵌套)
        FolderNode folder2 = new FolderNode("fld_2", "查询接口");
        folder1.getItems().add(folder2);

        // 三级 Request(嵌套在二级 Folder 中)
        RequestNode req = new RequestNode("req_1", "获取用户列表", "GET", "/users");
        req.getParams().add(new QueryParam("page", "1"));
        req.getHeaders().add(new Header("Accept", "application/json"));
        req.setAuth(AuthConfig.inherit());
        folder2.getItems().add(req);

        // 顶层 Request(直接挂在集合下)
        RequestNode topReq = new RequestNode("req_2", "创建用户", "POST", "/users");
        topReq.setBody(new RequestBody("raw"));
        topReq.getBody().setRawType("json");
        topReq.getBody().setContent("{\"name\":\"test\"}");
        col.getItems().add(topReq);

        // 写入 JSON 文件
        String path = "collections/test-nested-tree.json";
        store.write(path, col);

        // 读取并验证
        Collection loaded = store.read(path, Collection.class);
        assertNotNull(loaded);
        assertEquals("测试集合", loaded.getName());
        assertEquals("https://api.example.com/v1", loaded.getBaseUrl());
        assertEquals(2, loaded.getItems().size());

        // 验证一级 Folder
        CollectionItem item0 = loaded.getItems().get(0);
        assertTrue(item0.isFolder(), "第一个节点应为 Folder");
        FolderNode loadedFolder1 = (FolderNode) item0;
        assertEquals("用户模块", loadedFolder1.getName());
        assertEquals(1, loadedFolder1.getItems().size());

        // 验证二级 Folder(无限嵌套)
        CollectionItem nestedItem = loadedFolder1.getItems().get(0);
        assertTrue(nestedItem.isFolder(), "嵌套节点应为 Folder");
        FolderNode loadedFolder2 = (FolderNode) nestedItem;
        assertEquals("查询接口", loadedFolder2.getName());

        // 验证三级 Request
        CollectionItem deepItem = loadedFolder2.getItems().get(0);
        assertTrue(deepItem.isRequest(), "深层节点应为 Request");
        RequestNode loadedReq = (RequestNode) deepItem;
        assertEquals("GET", loadedReq.getMethod());
        assertEquals("/users", loadedReq.getUrl());
        assertEquals(1, loadedReq.getParams().size());
        assertEquals("page", loadedReq.getParams().get(0).getKey());
        assertEquals("inherit", loadedReq.getAuth().getType());

        // 验证顶层 Request
        CollectionItem item1 = loaded.getItems().get(1);
        assertTrue(item1.isRequest(), "第二个节点应为 Request");
        RequestNode loadedTopReq = (RequestNode) item1;
        assertEquals("POST", loadedTopReq.getMethod());
        assertEquals("raw", loadedTopReq.getBody().getType());
        assertEquals("json", loadedTopReq.getBody().getRawType());

        // 验证 findNode 递归查找
        CollectionItem found = loaded.findNode("req_1");
        assertNotNull(found, "应能递归找到深层节点");
        assertTrue(found.isRequest());

        // 验证 getAllRequests 扁平化提取
        assertEquals(2, loaded.getAllRequests().size());

        // 清理
        store.delete(path);
    }

    @Test
    void shouldSerializeAllAuthTypes() throws Exception {
        // 验证各种 AuthConfig 序列化
        RequestNode req = new RequestNode("req_auth", "认证测试", "GET", "/test");

        AuthConfig bearer = new AuthConfig("bearer");
        bearer.setToken("my-token");
        req.setAuth(bearer);

        String path = "collections/test-auth.json";
        store.write(path, req);

        RequestNode loaded = store.read(path, RequestNode.class);
        assertEquals("bearer", loaded.getAuth().getType());
        assertEquals("my-token", loaded.getAuth().getToken());

        store.delete(path);
    }

    @Test
    void shouldSerializeEnvironmentAndGlobals() throws Exception {
        // 环境
        Environment env = new Environment("env_dev", "开发环境");
        env.getVariables().add(new com.jcurl2.model.component.Variable("base_url", "http://localhost:8080"));
        env.getVariables().add(new com.jcurl2.model.component.Variable("secret_key", "encoded", true));

        String envPath = "environments/test-env.json";
        store.write(envPath, env);
        Environment loadedEnv = store.read(envPath, Environment.class);
        assertEquals("开发环境", loadedEnv.getName());
        assertEquals(2, loadedEnv.getVariables().size());
        assertTrue(loadedEnv.getVariables().get(1).isSecret());

        // 全局变量
        GlobalVariables globals = new GlobalVariables();
        globals.getVariables().add(new com.jcurl2.model.component.Variable("global_var", "global_value"));

        String globalsPath = "globals.json";
        store.write(globalsPath, globals);
        GlobalVariables loadedGlobals = store.read(globalsPath, GlobalVariables.class);
        assertEquals(1, loadedGlobals.getVariables().size());
        assertEquals("global_var", loadedGlobals.getVariables().get(0).getKey());

        // 清理
        store.delete(envPath);
        store.delete(globalsPath);
    }
}
