package com.jpostman2.service;

import com.jpostman2.config.AppConfig;
import com.jpostman2.model.Collection;
import com.jpostman2.model.CollectionItem;
import com.jpostman2.model.FolderNode;
import com.jpostman2.model.RequestNode;
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
 * CollectionService 集成测试 — 验证集合 CRUD 与树操作。
 * <p>
 * 使用 @TempDir 隔离测试数据目录,避免污染用户数据。
 */
@SpringBootTest(classes = AppConfig.class)
class CollectionServiceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("jpostman2.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private CollectionService collectionService;

    /** 记录本测试创建的集合 ID,用于测试后清理 */
    private java.util.List<String> createdCollectionIds = new java.util.ArrayList<>();

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

    /** 创建集合并记录 ID 以便清理 */
    private Collection createTestCollection(String name) {
        Collection collection = collectionService.createCollection(name);
        createdCollectionIds.add(collection.getId());
        return collection;
    }

    // ==================== 集合级 CRUD ====================

    @Nested
    @DisplayName("集合级 CRUD")
    class CollectionCrudTest {

        @Test
        @DisplayName("创建集合并持久化")
        void shouldCreateAndPersistCollection() {
            Collection collection = createTestCollection("测试集合A");

            assertNotNull(collection.getId());
            assertNotNull(collection.getCreatedAt());
            assertEquals("测试集合A", collection.getName());

            // 从磁盘重新加载
            Collection loaded = collectionService.loadCollection(collection.getId());
            assertNotNull(loaded);
            assertEquals("测试集合A", loaded.getName());
        }

        @Test
        @DisplayName("列举所有集合")
        void shouldListAllCollections() {
            createTestCollection("集合X");
            createTestCollection("集合Y");

            List<Collection> collections = collectionService.listCollections();
            assertTrue(collections.stream().anyMatch(c -> "集合X".equals(c.getName())));
            assertTrue(collections.stream().anyMatch(c -> "集合Y".equals(c.getName())));
        }

        @Test
        @DisplayName("删除集合后不再列出")
        void shouldDeleteCollection() {
            Collection collection = createTestCollection("待删除集合");
            String id = collection.getId();
            createdCollectionIds.remove(id); // 手动管理删除

            collectionService.deleteCollection(id);

            assertNull(collectionService.loadCollection(id));
        }
    }

    // ==================== 树操作 ====================

    @Nested
    @DisplayName("树操作")
    class TreeOperationTest {

        @Test
        @DisplayName("在根级别添加 Folder")
        void shouldAddFolderAtRoot() {
            Collection collection = createTestCollection("树测试");

            FolderNode folder = collectionService.addFolder(collection, null, "用户管理");

            assertNotNull(folder.getId());
            assertEquals(1, collection.getItems().size());
            assertTrue(collection.getItems().get(0).isFolder());
            assertEquals("用户管理", collection.getItems().get(0).getName());

            // 重新加载验证持久化
            Collection loaded = collectionService.loadCollection(collection.getId());
            assertEquals(1, loaded.getItems().size());
            assertEquals("用户管理", loaded.getItems().get(0).getName());
        }

        @Test
        @DisplayName("在根级别添加 Request")
        void shouldAddRequestAtRoot() {
            Collection collection = createTestCollection("树测试");

            RequestNode request = collectionService.addRequest(
                    collection, null, "获取用户", "GET", "/users");

            assertNotNull(request.getId());
            assertEquals(1, collection.getItems().size());
            assertTrue(collection.getItems().get(0).isRequest());
            assertEquals("GET", ((RequestNode) collection.getItems().get(0)).getMethod());
        }

        @Test
        @DisplayName("在 Folder 下嵌套添加子 Folder 和 Request")
        void shouldAddNestedItems() {
            Collection collection = createTestCollection("嵌套测试");

            FolderNode parent = collectionService.addFolder(collection, null, "父文件夹");
            collectionService.addFolder(collection, parent.getId(), "子文件夹");
            collectionService.addRequest(collection, parent.getId(), "嵌套请求", "POST", "/create");

            // 验证树结构
            assertEquals(1, collection.getItems().size());
            FolderNode loadedParent = (FolderNode) collection.getItems().get(0);
            assertEquals(2, loadedParent.getItems().size());
            assertTrue(loadedParent.getItems().get(0).isFolder());
            assertTrue(loadedParent.getItems().get(1).isRequest());
        }

        @Test
        @DisplayName("重命名节点")
        void shouldRenameNode() {
            Collection collection = createTestCollection("重命名测试");
            FolderNode folder = collectionService.addFolder(collection, null, "原名");

            collectionService.renameNode(collection, folder.getId(), "新名");

            assertEquals("新名", collection.getItems().get(0).getName());
        }

        @Test
        @DisplayName("删除节点")
        void shouldDeleteNode() {
            Collection collection = createTestCollection("删除测试");
            FolderNode folder = collectionService.addFolder(collection, null, "待删除");
            assertEquals(1, collection.getItems().size());

            collectionService.deleteNode(collection, folder.getId());

            assertEquals(0, collection.getItems().size());
        }

        @Test
        @DisplayName("删除 Folder 时连同子树一起删除")
        void shouldDeleteFolderWithSubtree() {
            Collection collection = createTestCollection("子树删除测试");
            FolderNode parent = collectionService.addFolder(collection, null, "父");
            collectionService.addRequest(collection, parent.getId(), "子请求", "GET", "/test");

            assertEquals(1, collection.getItems().size());

            collectionService.deleteNode(collection, parent.getId());

            assertEquals(0, collection.getItems().size());
        }

        @Test
        @DisplayName("移动节点到另一个 Folder 下")
        void shouldMoveNodeToAnotherFolder() {
            Collection collection = createTestCollection("移动测试");
            FolderNode folderA = collectionService.addFolder(collection, null, "文件夹A");
            FolderNode folderB = collectionService.addFolder(collection, null, "文件夹B");
            RequestNode request = collectionService.addRequest(collection, folderA.getId(), "请求1", "GET", "/test");

            // 将 request 从 folderA 移到 folderB
            collectionService.moveNode(collection, request.getId(), folderB.getId(), -1);

            // folderA 应为空
            assertEquals(0, ((FolderNode) collection.findNode(folderA.getId())).getItems().size());
            // folderB 应包含 request
            assertEquals(1, ((FolderNode) collection.findNode(folderB.getId())).getItems().size());
        }

        @Test
        @DisplayName("移动节点到根级别")
        void shouldMoveNodeToRoot() {
            Collection collection = createTestCollection("移动到根测试");
            FolderNode folder = collectionService.addFolder(collection, null, "文件夹");
            RequestNode request = collectionService.addRequest(collection, folder.getId(), "请求", "GET", "/test");

            collectionService.moveNode(collection, request.getId(), null, -1);

            // folder 应为空
            assertEquals(0, folder.getItems().size());
            // 根级别应有两个节点(folder + request)
            assertEquals(2, collection.getItems().size());
        }

        @Test
        @DisplayName("移动节点到指定位置")
        void shouldMoveNodeToSpecificIndex() {
            Collection collection = createTestCollection("索引移动测试");
            collectionService.addRequest(collection, null, "请求1", "GET", "/a");
            collectionService.addRequest(collection, null, "请求2", "GET", "/b");
            RequestNode req3 = collectionService.addRequest(collection, null, "请求3", "GET", "/c");

            // 将 req3 移到位置 0
            collectionService.moveNode(collection, req3.getId(), null, 0);

            assertEquals("请求3", collection.getItems().get(0).getName());
            assertEquals("请求1", collection.getItems().get(1).getName());
        }

        @Test
        @DisplayName("循环检测: 不能将 Folder 移入自身")
        void shouldRejectMoveFolderIntoItself() {
            Collection collection = createTestCollection("循环检测1");
            FolderNode folder = collectionService.addFolder(collection, null, "文件夹");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> collectionService.moveNode(collection, folder.getId(), folder.getId(), -1));
            assertTrue(ex.getMessage().contains("自身"));
        }

        @Test
        @DisplayName("循环检测: 不能将 Folder 移入其子 Folder")
        void shouldRejectMoveFolderIntoDescendant() {
            Collection collection = createTestCollection("循环检测2");
            FolderNode parent = collectionService.addFolder(collection, null, "父文件夹");
            FolderNode child = collectionService.addFolder(collection, parent.getId(), "子文件夹");

            // 尝试将 parent 移入 child → 应抛异常
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> collectionService.moveNode(collection, parent.getId(), child.getId(), -1));
            assertTrue(ex.getMessage().contains("子文件夹") || ex.getMessage().contains("子树"));
        }

        @Test
        @DisplayName("复制请求节点")
        void shouldDuplicateRequest() {
            Collection collection = createTestCollection("复制测试");
            RequestNode original = collectionService.addRequest(
                    collection, null, "原始请求", "POST", "/api");
            original.getHeaders().add(new com.jpostman2.model.component.Header("X-Custom", "val"));

            RequestNode copy = collectionService.duplicateRequest(collection, original.getId());

            assertNotEquals(original.getId(), copy.getId());
            assertTrue(copy.getName().endsWith("_copy"));
            assertEquals("POST", copy.getMethod());
            assertEquals("/api", copy.getUrl());
            // 验证 headers 被复制
            assertEquals(1, copy.getHeaders().size());
            // 集合中应有两个节点
            assertEquals(2, collection.getItems().size());
        }

        @Test
        @DisplayName("重新排序节点")
        void shouldReorderNodes() {
            Collection collection = createTestCollection("排序测试");
            RequestNode req1 = collectionService.addRequest(collection, null, "A", "GET", "/a");
            RequestNode req2 = collectionService.addRequest(collection, null, "B", "GET", "/b");
            RequestNode req3 = collectionService.addRequest(collection, null, "C", "GET", "/c");

            // 原始顺序: A, B, C → 新顺序: C, A, B
            collectionService.reorderNodes(collection, null,
                    List.of(req3.getId(), req1.getId(), req2.getId()));

            assertEquals("C", collection.getItems().get(0).getName());
            assertEquals("A", collection.getItems().get(1).getName());
            assertEquals("B", collection.getItems().get(2).getName());
        }
    }

    // ==================== 集成: 加载后树结构完整 ====================

    @Nested
    @DisplayName("持久化验证")
    class PersistenceTest {

        @Test
        @DisplayName("复杂嵌套树保存后完整还原")
        void shouldRestoreComplexTreeAfterReload() {
            Collection collection = createTestCollection("持久化测试");

            FolderNode folder1 = collectionService.addFolder(collection, null, "模块A");
            collectionService.addRequest(collection, folder1.getId(), "请求A1", "GET", "/a1");
            collectionService.addRequest(collection, folder1.getId(), "请求A2", "POST", "/a2");

            FolderNode subFolder = collectionService.addFolder(collection, folder1.getId(), "子模块");
            collectionService.addRequest(collection, subFolder.getId(), "深层请求", "PUT", "/deep");

            collectionService.addRequest(collection, null, "根请求", "DELETE", "/root");

            // 重新加载
            Collection loaded = collectionService.loadCollection(collection.getId());

            // 根级别: 2个节点(folder1 + 根请求)
            assertEquals(2, loaded.getItems().size());

            // 找到 folder1
            FolderNode loadedFolder1 = (FolderNode) loaded.getItems().stream()
                    .filter(CollectionItem::isFolder)
                    .findFirst()
                    .orElseThrow();
            assertEquals("模块A", loadedFolder1.getName());
            assertEquals(3, loadedFolder1.getItems().size()); // 请求A1 + 请求A2 + 子模块

            // 找到子模块
            FolderNode loadedSub = (FolderNode) loadedFolder1.getItems().stream()
                    .filter(CollectionItem::isFolder)
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, loadedSub.getItems().size());
            assertEquals("深层请求", loadedSub.getItems().get(0).getName());
        }
    }
}
