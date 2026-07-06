package com.jpostman2.plugin;

import com.jpostman2.model.dto.RequestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件系统测试 — 验证 PluginManager、JavaSourceCompiler、ExtensionRegistry 核心功能。
 * <p>
 * 不依赖 Spring 容器,直接构造组件测试,保证隔离性。
 */
class PluginManagerTest {

    @TempDir
    Path tempDir;

    // ==================== stripComments 测试 ====================

    @Nested
    @DisplayName("注释去除")
    class StripCommentsTest {

        @Test
        @DisplayName("去除单行注释")
        void stripLineComment() {
            String code = "int x = 1; // 这是注释\nint y = 2;";
            String result = PluginManager.stripComments(code);
            assertFalse(result.contains("这是注释"));
            assertTrue(result.contains("int y = 2;"));
        }

        @Test
        @DisplayName("去除多行注释")
        void stripBlockComment() {
            String code = "/* 这是\n多行注释 */\nint x = 1;";
            String result = PluginManager.stripComments(code);
            assertFalse(result.contains("多行注释"));
            assertTrue(result.contains("int x = 1;"));
        }

        @Test
        @DisplayName("保留字符串内的注释标记")
        void preserveStringLiteral() {
            String code = "String s = \"// not a comment\";";
            String result = PluginManager.stripComments(code);
            assertTrue(result.contains("// not a comment"));
        }

        @Test
        @DisplayName("保留字符字面量")
        void preserveCharLiteral() {
            String code = "char c = '/';";
            String result = PluginManager.stripComments(code);
            assertTrue(result.contains("'/'"));
        }
    }

    // ==================== ExtensionRegistry 测试 ====================

    @Nested
    @DisplayName("扩展注册表")
    class ExtensionRegistryTest {

        @Test
        @DisplayName("注册和获取扩展")
        void registerAndGet() {
            ExtensionRegistry registry = new ExtensionRegistry();
            TestExtension ext = new TestExtension();
            registry.register("plugin1", ext);

            assertEquals(1, registry.getExtensions(TestExtensionPoint.class).size());
            assertSame(ext, registry.getExtensions(TestExtensionPoint.class).get(0));
        }

        @Test
        @DisplayName("卸载插件的扩展")
        void unregisterPlugin() {
            ExtensionRegistry registry = new ExtensionRegistry();
            TestExtension ext1 = new TestExtension();
            TestExtension ext2 = new TestExtension();
            registry.register("plugin1", ext1);
            registry.register("plugin2", ext2);

            assertEquals(2, registry.getExtensions(TestExtensionPoint.class).size());

            registry.unregister("plugin1");
            assertEquals(1, registry.getExtensions(TestExtensionPoint.class).size());
            assertSame(ext2, registry.getExtensions(TestExtensionPoint.class).get(0));
        }

        @Test
        @DisplayName("获取不存在的扩展类型返回空列表")
        void getNonExistentType() {
            ExtensionRegistry registry = new ExtensionRegistry();
            assertTrue(registry.getExtensions(TestExtensionPoint.class).isEmpty());
        }

        @Test
        @DisplayName("清除所有注册")
        void clearAll() {
            ExtensionRegistry registry = new ExtensionRegistry();
            registry.register("p1", new TestExtension());
            registry.register("p2", new TestExtension());
            registry.clear();
            assertTrue(registry.getExtensions(TestExtensionPoint.class).isEmpty());
        }
    }

    // ==================== 插件加载集成测试 ====================

    @Nested
    @DisplayName("插件加载")
    class PluginLoadingTest {

        @Test
        @DisplayName("编译并加载简单插件")
        void loadSimplePlugin() throws Exception {
            Path pluginsDir = tempDir.resolve("plugins");
            Files.createDirectories(pluginsDir);

            // 实际可编译的插件代码
            String code = """
                import com.jpostman2.plugin.PluginContext;
                import com.jpostman2.plugin.extension.RequestInterceptor;
                import com.jpostman2.model.dto.RequestConfig;

                public class TestSignPlugin implements RequestInterceptor {
                    @Override
                    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
                        config.getHeaders().add(
                            new com.jpostman2.model.component.Header("X-Sign", "signed"));
                        return config;
                    }
                }
                """;

            Files.writeString(pluginsDir.resolve("TestSignPlugin.java"), code);

            PluginManager manager = new PluginManager(pluginsDir, null);
            manager.loadAll();

            assertEquals(1, manager.listPlugins().size());
            Plugin plugin = manager.listPlugins().get(0);
            assertEquals("TestSignPlugin", plugin.getId());
            assertEquals(Plugin.LoadStatus.LOADED, plugin.getStatus());
            assertTrue(plugin.getExtensionPoints().contains("TestSignPlugin"));

            // 验证扩展已注册
            var interceptors = manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class);
            assertEquals(1, interceptors.size());

            // 验证拦截器功能
            var interceptor = interceptors.get(0);
            RequestConfig config = new RequestConfig("GET", "https://example.com");
            interceptor.beforeRequest(config, null);
            assertEquals(1, config.getHeaders().size());
            assertEquals("X-Sign", config.getHeaders().get(0).getKey());
            assertEquals("signed", config.getHeaders().get(0).getValue());
        }

        @Test
        @DisplayName("加载带 @JPostmanPlugin 注解的插件")
        void loadAnnotatedPlugin() throws Exception {
            Path pluginsDir = tempDir.resolve("plugins");
            Files.createDirectories(pluginsDir);

            String code = """
                import com.jpostman2.plugin.JPostmanPlugin;
                import com.jpostman2.plugin.PluginContext;
                import com.jpostman2.plugin.extension.ResponseInterceptor;
                import com.jpostman2.model.dto.RequestConfig;
                import com.jpostman2.model.dto.ResponseData;

                @JPostmanPlugin(name = "日志插件", description = "记录响应日志", version = "2.0.0", author = "tester")
                public class LogPlugin implements ResponseInterceptor {
                    @Override
                    public ResponseData afterResponse(ResponseData response, RequestConfig config, PluginContext ctx) {
                        return response;
                    }
                }
                """;

            Files.writeString(pluginsDir.resolve("LogPlugin.java"), code);

            PluginManager manager = new PluginManager(pluginsDir, null);
            manager.loadAll();

            Plugin plugin = manager.getPlugin("LogPlugin");
            assertNotNull(plugin);
            assertEquals("日志插件", plugin.getName());
            assertEquals("记录响应日志", plugin.getDescription());
            assertEquals("2.0.0", plugin.getVersion());
            assertEquals("tester", plugin.getAuthor());
        }

        @Test
        @DisplayName("编译失败的插件记录错误状态")
        void loadFailedPlugin() throws Exception {
            Path pluginsDir = tempDir.resolve("plugins");
            Files.createDirectories(pluginsDir);

            // 故意写有语法错误的代码
            String code = "public class BrokenPlugin { invalid syntax }";
            Files.writeString(pluginsDir.resolve("BrokenPlugin.java"), code);

            PluginManager manager = new PluginManager(pluginsDir, null);
            manager.loadAll();

            Plugin plugin = manager.getPlugin("BrokenPlugin");
            assertNotNull(plugin);
            assertEquals(Plugin.LoadStatus.FAILED, plugin.getStatus());
            assertNotNull(plugin.getErrorMessage());
        }

        @Test
        @DisplayName("禁用插件后扩展不生效")
        void disablePlugin() throws Exception {
            Path pluginsDir = tempDir.resolve("plugins");
            Files.createDirectories(pluginsDir);

            String code = """
                import com.jpostman2.plugin.PluginContext;
                import com.jpostman2.plugin.extension.RequestInterceptor;
                import com.jpostman2.model.dto.RequestConfig;

                public class DisPlugin implements RequestInterceptor {
                    @Override
                    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
                        return config;
                    }
                }
                """;

            Files.writeString(pluginsDir.resolve("DisPlugin.java"), code);

            PluginManager manager = new PluginManager(pluginsDir, null);
            manager.loadAll();

            assertEquals(1, manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class).size());

            manager.disablePlugin("DisPlugin");
            assertEquals(0, manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class).size());

            manager.enablePlugin("DisPlugin");
            assertEquals(1, manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class).size());
        }

        @Test
        @DisplayName("卸载插件后扩展被清除")
        void unloadPlugin() throws Exception {
            Path pluginsDir = tempDir.resolve("plugins");
            Files.createDirectories(pluginsDir);

            String code = """
                import com.jpostman2.plugin.PluginContext;
                import com.jpostman2.plugin.extension.RequestInterceptor;
                import com.jpostman2.model.dto.RequestConfig;

                public class UnloadPlugin implements RequestInterceptor {
                    @Override
                    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
                        return config;
                    }
                }
                """;

            Files.writeString(pluginsDir.resolve("UnloadPlugin.java"), code);

            PluginManager manager = new PluginManager(pluginsDir, null);
            manager.loadAll();

            assertEquals(1, manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class).size());

            manager.unloadPlugin("UnloadPlugin");
            assertEquals(0, manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class).size());
        }

        @Test
        @DisplayName("重载插件")
        void reloadPlugin() throws Exception {
            Path pluginsDir = tempDir.resolve("plugins");
            Files.createDirectories(pluginsDir);

            String code = """
                import com.jpostman2.plugin.PluginContext;
                import com.jpostman2.plugin.extension.RequestInterceptor;
                import com.jpostman2.model.dto.RequestConfig;

                public class ReloadPlugin implements RequestInterceptor {
                    @Override
                    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
                        return config;
                    }
                }
                """;

            Files.writeString(pluginsDir.resolve("ReloadPlugin.java"), code);

            PluginManager manager = new PluginManager(pluginsDir, null);
            manager.loadAll();

            // 修改源码后重载
            String updatedCode = """
                import com.jpostman2.plugin.PluginContext;
                import com.jpostman2.plugin.extension.RequestInterceptor;
                import com.jpostman2.model.dto.RequestConfig;

                public class ReloadPlugin implements RequestInterceptor {
                    @Override
                    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
                        config.getHeaders().add(
                            new com.jpostman2.model.component.Header("X-Reload", "true"));
                        return config;
                    }
                }
                """;
            Files.writeString(pluginsDir.resolve("ReloadPlugin.java"), updatedCode);

            manager.reloadPlugin("ReloadPlugin");

            var interceptors = manager.getRegistry()
                    .getExtensions(com.jpostman2.plugin.extension.RequestInterceptor.class);
            assertEquals(1, interceptors.size());

            RequestConfig config = new RequestConfig("GET", "https://example.com");
            interceptors.get(0).beforeRequest(config, null);
            assertEquals("X-Reload", config.getHeaders().get(0).getKey());
        }
    }

    // ==================== 测试用扩展点 ====================

    /** 测试用扩展点接口 */
    interface TestExtensionPoint extends ExtensionPoint {
        String doSomething();
    }

    /** 测试用扩展实现 */
    static class TestExtension implements TestExtensionPoint {
        @Override
        public String doSomething() {
            return "test";
        }
    }
}
