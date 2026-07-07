package com.jcurl.store;

import com.jcurl.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonStoreService 集成测试 — 验证 SpringBoot 容器启动与数据目录初始化。
 */
@SpringBootTest(classes = AppConfig.class)
class JsonStoreServiceTest {

    @Autowired
    private JsonStoreService storeService;

    @Test
    void shouldInitializeAllDataDirectories() {
        Path base = storeService.getBaseDir();
        assertTrue(Files.isDirectory(base), "根目录应已创建");
        assertTrue(Files.isDirectory(base.resolve("collections")), "collections/ 应已创建");
        assertTrue(Files.isDirectory(base.resolve("environments")), "environments/ 应已创建");
        assertTrue(Files.isDirectory(base.resolve("reports")), "reports/ 应已创建");
        assertTrue(Files.isDirectory(base.resolve("plugins/loaded")), "plugins/loaded/ 应已创建");
        assertTrue(Files.isDirectory(base.resolve("plugins/disabled")), "plugins/disabled/ 应已创建");
        assertTrue(Files.isDirectory(base.resolve("plugins/logs")), "plugins/logs/ 应已创建");
    }

    @Test
    void shouldWriteAndReadJsonFile() {
        TestPojo data = new TestPojo();
        data.name = "测试集合";
        data.value = 42;

        storeService.write("collections/test-temp.json", data);
        TestPojo read = storeService.read("collections/test-temp.json", TestPojo.class);

        assertTrue(read != null, "读取结果不应为 null");
        assertTrue("测试集合".equals(read.name), "name 应一致");
        assertTrue(read.value == 42, "value 应一致");

        // 清理临时文件
        storeService.delete("collections/test-temp.json");
    }

    static class TestPojo {
        public String name;
        public int value;
    }
}
