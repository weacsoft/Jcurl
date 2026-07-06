package com.jcurl2.service;

import com.jcurl2.config.AppConfig;
import com.jcurl2.model.Collection;
import com.jcurl2.model.Environment;
import com.jcurl2.model.GlobalVariables;
import com.jcurl2.model.component.Variable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 变量系统测试 — 验证 4 级作用域优先级、动态函数、Secret 编解码、自动补全。
 */
@SpringBootTest(classes = AppConfig.class)
class VariableResolverTest {

    @Autowired
    private VariableResolver resolver;

    @Autowired
    private VariableFunctionProvider functionProvider;

    @Test
    void shouldResolveByPriorityLocalOverEnv() {
        // 同名变量在 Local 和 Environment 中都有,Local 优先
        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("host", "env.example.com"));

        VariableScope scope = new VariableScope(
                Map.of("host", "local.example.com"), env, null, null);

        String result = resolver.resolve("https://{{host}}/api", scope);
        assertEquals("https://local.example.com/api", result);
    }

    @Test
    void shouldResolveByPriorityEnvOverCollection() {
        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("host", "env.example.com"));

        Collection col = new Collection("col1", "集合");
        col.getVariables().add(new Variable("host", "col.example.com"));

        VariableScope scope = new VariableScope(null, env, col, null);
        String result = resolver.resolve("https://{{host}}/api", scope);
        assertEquals("https://env.example.com/api", result);
    }

    @Test
    void shouldResolveByPriorityCollectionOverGlobal() {
        Collection col = new Collection("col1", "集合");
        col.getVariables().add(new Variable("host", "col.example.com"));

        GlobalVariables globals = new GlobalVariables();
        globals.getVariables().add(new Variable("host", "global.example.com"));

        VariableScope scope = new VariableScope(null, null, col, globals);
        String result = resolver.resolve("https://{{host}}/api", scope);
        assertEquals("https://col.example.com/api", result);
    }

    @Test
    void shouldFallbackToLowerPriority() {
        // Environment 没有该变量,回退到 Collection
        Environment env = new Environment("env1", "开发");
        Collection col = new Collection("col1", "集合");
        col.getVariables().add(new Variable("token", "col-token"));

        VariableScope scope = new VariableScope(null, env, col, null);
        String result = resolver.resolve("Bearer {{token}}", scope);
        assertEquals("Bearer col-token", result);
    }

    @Test
    void shouldResolveGlobalVariable() {
        GlobalVariables globals = new GlobalVariables();
        globals.getVariables().add(new Variable("api_key", "global-key-123"));

        VariableScope scope = new VariableScope(null, null, null, globals);
        String result = resolver.resolve("key={{api_key}}", scope);
        assertEquals("key=global-key-123", result);
    }

    @Test
    void shouldKeepUnresolvedVariableAsIs() {
        VariableScope scope = VariableScope.empty();
        String result = resolver.resolve("https://{{unknown}}/api", scope);
        assertEquals("https://{{unknown}}/api", result);
    }

    @Test
    void shouldResolveTimestampFunction() {
        String result = functionProvider.resolve("ts={{$timestamp}}");
        assertTrue(result.startsWith("ts="));
        long ts = Long.parseLong(result.substring(3));
        assertTrue(ts > 1000000000L, "时间戳应是一个合理的 Unix 秒值");
    }

    @Test
    void shouldResolveUuidFunction() {
        String result = functionProvider.resolve("id={{$uuid}}");
        assertTrue(result.startsWith("id="));
        String uuid = result.substring(3);
        // UUID 格式: 8-4-4-4-12
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "应生成有效 UUID: " + uuid);
    }

    @Test
    void shouldResolveRandomIntFunction() {
        String result = functionProvider.resolve("n={{$randomInt}}");
        assertTrue(result.startsWith("n="));
        int n = Integer.parseInt(result.substring(2));
        assertTrue(n >= 0 && n <= 1000, "随机整数应在 0-1000 范围内");
    }

    @Test
    void shouldResolveRandomIntWithRange() {
        String result = functionProvider.resolve("n={{$randomInt 100 200}}");
        int n = Integer.parseInt(result.substring(2));
        assertTrue(n >= 100 && n <= 200, "随机整数应在 100-200 范围内");
    }

    @Test
    void shouldResolveDatetimeFunction() {
        String result = functionProvider.resolve("dt={{$datetime}}");
        assertTrue(result.startsWith("dt="));
        // ISO 日期时间格式
        String dt = result.substring(3);
        assertTrue(dt.contains("T") && dt.contains("Z"), "应为 ISO 格式: " + dt);
    }

    @Test
    void shouldResolveMixedVariablesAndFunctions() {
        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("host", "api.example.com"));

        VariableScope scope = new VariableScope(null, env, null, null);
        String result = resolver.resolve(
                "https://{{host}}/users?ts={{$timestamp}}&id={{$uuid}}", scope);

        assertTrue(result.startsWith("https://api.example.com/users?ts="));
        assertTrue(result.contains("&id="));
    }

    @Test
    void shouldDecodeSecretVariable() {
        // Secret 变量存储时 Base64 编码
        String plainSecret = "my-secret-password";
        String encoded = VariableResolver.encodeSecret(plainSecret);

        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("password", encoded, true));

        VariableScope scope = new VariableScope(null, env, null, null);
        String result = resolver.resolve("pass={{password}}", scope);
        assertEquals("pass=" + plainSecret, result);
    }

    @Test
    void shouldEncodeAndDecodeSecret() {
        String original = "test-secret-123!@#";
        String encoded = VariableResolver.encodeSecret(original);
        String decoded = VariableResolver.decodeSecret(encoded);
        assertEquals(original, decoded);
    }

    @Test
    void shouldHandleSecretDecodeFailureGracefully() {
        // 非 Base64 值不应抛异常
        String decoded = VariableResolver.decodeSecret("not-base64!!!");
        assertEquals("not-base64!!!", decoded);
    }

    @Test
    void shouldListAvailableVariablesForAutocomplete() {
        GlobalVariables globals = new GlobalVariables();
        globals.getVariables().add(new Variable("global_var", "global-val"));

        Collection col = new Collection("col1", "集合");
        col.getVariables().add(new Variable("col_var", "col-val"));
        col.getVariables().add(new Variable("shared", "col-shared"));

        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("env_var", "env-val"));
        env.getVariables().add(new Variable("shared", "env-shared")); // 覆盖集合级

        VariableScope scope = new VariableScope(
                Map.of("local_var", "local-val"), env, col, globals);

        List<VariableEntry> entries = resolver.listAvailable(scope);

        // 应包含所有变量
        assertEquals(5, entries.size(), "应有 5 个变量(global_var, col_var, shared, env_var, local_var)");

        // shared 应被 Environment 覆盖(高优先级)
        VariableEntry sharedEntry = entries.stream()
                .filter(e -> "shared".equals(e.getKey())).findFirst().orElse(null);
        assertNotNull(sharedEntry);
        assertEquals("Environment", sharedEntry.getScope());

        // local_var 应存在
        VariableEntry localEntry = entries.stream()
                .filter(e -> "local_var".equals(e.getKey())).findFirst().orElse(null);
        assertNotNull(localEntry);
        assertEquals("Local", localEntry.getScope());
    }

    @Test
    void shouldResolveMultipleVariablesInOneString() {
        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("host", "api.example.com"));
        env.getVariables().add(new Variable("port", "8080"));

        VariableScope scope = new VariableScope(null, env, null, null);
        String result = resolver.resolve("https://{{host}}:{{port}}/api", scope);
        assertEquals("https://api.example.com:8080/api", result);
    }

    @Test
    void shouldResolveVariableInMultipleScopes() {
        // 不同变量在不同作用域,应全部解析
        GlobalVariables globals = new GlobalVariables();
        globals.getVariables().add(new Variable("g", "global"));

        Collection col = new Collection("col1", "集合");
        col.getVariables().add(new Variable("c", "collection"));

        Environment env = new Environment("env1", "开发");
        env.getVariables().add(new Variable("e", "env"));

        VariableScope scope = new VariableScope(Map.of("l", "local"), env, col, globals);
        String result = resolver.resolve("{{l}}-{{e}}-{{c}}-{{g}}", scope);
        assertEquals("local-env-collection-global", result);
    }
}
