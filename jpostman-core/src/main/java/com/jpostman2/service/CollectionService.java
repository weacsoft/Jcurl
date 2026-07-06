package com.jpostman2.service;

import com.jpostman2.model.Collection;
import com.jpostman2.model.CollectionItem;
import com.jpostman2.model.FolderNode;
import com.jpostman2.model.RequestNode;
import com.jpostman2.store.JsonStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 集合管理服务 — 负责集合的持久化 CRUD 与集合树结构操作。
 * <p>
 * 核心能力:
 * <ul>
 *   <li>集合级 CRUD: 创建、列举、加载、保存、删除</li>
 *   <li>树操作: 添加 Folder/Request、移动节点、重命名、删除、排序</li>
 *   <li>循环检测: 移动 Folder 时防止将其移入自身或子树</li>
 *   <li>自动持久化: 每次树操作后自动保存集合到磁盘</li>
 * </ul>
 * <p>
 * 文件存储路径: {@code ~/.api-client/collections/{collectionId}.json}
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private static final String COLLECTIONS_DIR = "collections";

    private final JsonStoreService store;

    public CollectionService(JsonStoreService store) {
        this.store = store;
    }

    // ==================== 集合级 CRUD ====================

    /**
     * 创建新集合并持久化。
     *
     * @param name 集合名称
     * @return 已创建的 Collection(含生成的 ID)
     */
    public Collection createCollection(String name) {
        String id = UUID.randomUUID().toString();
        Collection collection = new Collection(id, name);
        saveCollection(collection);
        log.info("创建集合: id={}, name={}", id, name);
        return collection;
    }

    /**
     * 列举所有已存储的集合(完整加载)。
     *
     * @return 集合列表,无集合时返回空列表
     */
    public List<Collection> listCollections() {
        List<Collection> result = new ArrayList<>();
        List<Path> files = store.listFiles(COLLECTIONS_DIR);
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".json") || fileName.endsWith(".tmp")) {
                continue;
            }
            String relativePath = COLLECTIONS_DIR + "/" + fileName;
            try {
                Collection collection = store.read(relativePath, Collection.class);
                if (collection != null) {
                    result.add(collection);
                }
            } catch (Exception e) {
                log.warn("加载集合文件失败: {}, 跳过", fileName, e);
            }
        }
        return result;
    }

    /**
     * 加载指定集合。
     *
     * @param collectionId 集合 ID
     * @return 集合对象,不存在时返回 null
     */
    public Collection loadCollection(String collectionId) {
        return store.read(COLLECTIONS_DIR + "/" + collectionId + ".json", Collection.class);
    }

    /**
     * 保存集合到磁盘(原子写入)。
     *
     * @param collection 要保存的集合
     */
    public void saveCollection(Collection collection) {
        collection.setUpdatedAt(LocalDateTime.now());
        store.write(COLLECTIONS_DIR + "/" + collection.getId() + ".json", collection);
    }

    /**
     * 删除指定集合。
     *
     * @param collectionId 集合 ID
     */
    public void deleteCollection(String collectionId) {
        store.delete(COLLECTIONS_DIR + "/" + collectionId + ".json");
        log.info("删除集合: id={}", collectionId);
    }

    // ==================== 树操作 ====================

    /**
     * 在指定父节点下添加 Folder。parentId 为 null 时添加到集合根。
     *
     * @param collection 目标集合
     * @param parentId   父节点 ID(null 表示根级别)
     * @param folderName 文件夹名称
     * @return 已创建的 FolderNode
     */
    public FolderNode addFolder(Collection collection, String parentId, String folderName) {
        FolderNode folder = new FolderNode(UUID.randomUUID().toString(), folderName);
        List<CollectionItem> targetList = getChildrenList(collection, parentId);
        targetList.add(folder);
        saveCollection(collection);
        log.debug("添加 Folder: collection={}, parent={}, folder={}",
                collection.getId(), parentId, folderName);
        return folder;
    }

    /**
     * 在指定父节点下添加 Request。parentId 为 null 时添加到集合根。
     *
     * @param collection  目标集合
     * @param parentId    父节点 ID(null 表示根级别)
     * @param requestName 请求名称
     * @param method      HTTP 方法
     * @param url         请求 URL
     * @return 已创建的 RequestNode
     */
    public RequestNode addRequest(Collection collection, String parentId,
                                  String requestName, String method, String url) {
        RequestNode request = new RequestNode(
                UUID.randomUUID().toString(), requestName, method, url);
        List<CollectionItem> targetList = getChildrenList(collection, parentId);
        targetList.add(request);
        saveCollection(collection);
        log.debug("添加 Request: collection={}, parent={}, name={}, method={}",
                collection.getId(), parentId, requestName, method);
        return request;
    }

    /**
     * 移动节点到新的父节点下(可指定插入位置)。
     * <p>
     * 循环检测: 如果 sourceNode 是 Folder,不能将其移入自身或其子树。
     *
     * @param collection     目标集合
     * @param nodeId         要移动的节点 ID
     * @param targetParentId 目标父节点 ID(null 表示根级别)
     * @param targetIndex    在目标父节点中的插入位置(-1 表示追加到末尾)
     * @throws IllegalArgumentException 如果移动会导致循环
     */
    public void moveNode(Collection collection, String nodeId,
                         String targetParentId, int targetIndex) {
        CollectionItem node = collection.findNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: " + nodeId);
        }

        // 循环检测: Folder 不能移入自身或其子树
        if (node.isFolder() && targetParentId != null) {
            if (nodeId.equals(targetParentId)) {
                throw new IllegalArgumentException("不能将文件夹移入自身");
            }
            if (isDescendant((FolderNode) node, targetParentId)) {
                throw new IllegalArgumentException("不能将文件夹移入其子文件夹");
            }
        }

        // 从原位置移除
        List<CollectionItem> oldList = findParentList(collection, nodeId);
        if (oldList != null) {
            oldList.removeIf(item -> nodeId.equals(item.getId()));
        }

        // 插入到新位置
        List<CollectionItem> targetList = getChildrenList(collection, targetParentId);
        if (targetIndex < 0 || targetIndex >= targetList.size()) {
            targetList.add(node);
        } else {
            targetList.add(targetIndex, node);
        }

        saveCollection(collection);
        log.debug("移动节点: node={}, targetParent={}, index={}", nodeId, targetParentId, targetIndex);
    }

    /**
     * 重命名节点。
     *
     * @param collection 目标集合
     * @param nodeId     节点 ID
     * @param newName    新名称
     */
    public void renameNode(Collection collection, String nodeId, String newName) {
        CollectionItem node = collection.findNode(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: " + nodeId);
        }
        node.setName(newName);
        saveCollection(collection);
        log.debug("重命名节点: node={}, newName={}", nodeId, newName);
    }

    /**
     * 删除节点(含子树)。
     *
     * @param collection 目标集合
     * @param nodeId     节点 ID
     */
    public void deleteNode(Collection collection, String nodeId) {
        List<CollectionItem> parentList = findParentList(collection, nodeId);
        if (parentList != null) {
            boolean removed = parentList.removeIf(item -> nodeId.equals(item.getId()));
            if (removed) {
                saveCollection(collection);
                log.debug("删除节点: node={}", nodeId);
            }
        }
    }

    /**
     * 复制请求节点(生成新 ID,名称加 "_copy" 后缀)。
     *
     * @param collection 目标集合
     * @param nodeId     要复制的 Request 节点 ID
     * @return 新创建的 RequestNode
     */
    public RequestNode duplicateRequest(Collection collection, String nodeId) {
        CollectionItem node = collection.findNode(nodeId);
        if (node == null || !node.isRequest()) {
            throw new IllegalArgumentException("请求节点不存在: " + nodeId);
        }
        RequestNode original = (RequestNode) node;
        RequestNode copy = new RequestNode(
                UUID.randomUUID().toString(),
                original.getName() + "_copy",
                original.getMethod(),
                original.getUrl());
        // 复制 params/headers/body/auth
        copy.getParams().addAll(original.getParams());
        copy.getHeaders().addAll(original.getHeaders());
        copy.setBody(original.getBody());
        copy.setAuth(original.getAuth());

        // 插入到原节点后面
        List<CollectionItem> parentList = findParentList(collection, nodeId);
        if (parentList != null) {
            int index = -1;
            for (int i = 0; i < parentList.size(); i++) {
                if (nodeId.equals(parentList.get(i).getId())) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                parentList.add(index + 1, copy);
            } else {
                parentList.add(copy);
            }
        } else {
            collection.getItems().add(copy);
        }

        saveCollection(collection);
        log.debug("复制请求: source={}, copy={}", nodeId, copy.getId());
        return copy;
    }

    /**
     * 在同一父节点内重新排序子节点。
     *
     * @param collection 目标集合
     * @param parentId   父节点 ID(null 表示根级别)
     * @param nodeIds    按新顺序排列的节点 ID 列表
     */
    public void reorderNodes(Collection collection, String parentId, List<String> nodeIds) {
        List<CollectionItem> children = getChildrenList(collection, parentId);
        List<CollectionItem> reordered = new ArrayList<>();
        for (String id : nodeIds) {
            children.stream()
                    .filter(item -> id.equals(item.getId()))
                    .findFirst()
                    .ifPresent(reordered::add);
        }
        // 保留未在 nodeIds 中出现的节点(追加到末尾)
        children.stream()
                .filter(item -> !nodeIds.contains(item.getId()))
                .forEach(reordered::add);

        children.clear();
        children.addAll(reordered);
        saveCollection(collection);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 获取指定父节点的子节点列表。parentId 为 null 时返回集合根列表。
     */
    private List<CollectionItem> getChildrenList(Collection collection, String parentId) {
        if (parentId == null) {
            return collection.getItems();
        }
        CollectionItem parent = collection.findNode(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("父节点不存在: " + parentId);
        }
        if (!parent.isFolder()) {
            throw new IllegalArgumentException("父节点不是文件夹: " + parentId);
        }
        return ((FolderNode) parent).getItems();
    }

    /**
     * 查找节点所在的子列表(即其父节点的 items 列表)。
     * 递归搜索整个集合树。
     *
     * @param collection 目标集合
     * @param nodeId     要查找的节点 ID
     * @return 节点所在的列表,未找到返回 null
     */
    private List<CollectionItem> findParentList(Collection collection, String nodeId) {
        return findParentListInItems(collection.getItems(), nodeId);
    }

    private List<CollectionItem> findParentListInItems(List<CollectionItem> items, String nodeId) {
        for (CollectionItem item : items) {
            if (nodeId.equals(item.getId())) {
                return items;
            }
            if (item.isFolder()) {
                List<CollectionItem> found = findParentListInItems(((FolderNode) item).getItems(), nodeId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 检查 targetId 是否是 folder 的后代节点(用于循环检测)。
     */
    private boolean isDescendant(FolderNode folder, String targetId) {
        for (CollectionItem child : folder.getItems()) {
            if (targetId.equals(child.getId())) {
                return true;
            }
            if (child.isFolder() && isDescendant((FolderNode) child, targetId)) {
                return true;
            }
        }
        return false;
    }
}
