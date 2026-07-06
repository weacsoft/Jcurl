package com.jcurl.service;

import com.jcurl.model.CollectionFile;
import com.jcurl.model.CollectionItem;
import com.jcurl.model.FolderNode;
import com.jcurl.model.RequestNode;
import com.jcurl.service.store.CollectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 集合管理服务 — v2 架构。
 * <p>
 * 基于 JSON 文件存储, 管理集合(Collection)的 CRUD 操作,
 * 以及集合内部树形结构(Folder/Request)的增删改查。
 * <p>
 * 集合级继承配置:
 * - baseUrl: 集合下所有请求的 URL 自动拼接此前缀
 * - headers: 集合下所有请求自动携带的公共请求头
 * - auth: 集合下所有请求默认使用的认证 (请求可覆盖)
 * - variables: 集合级变量
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private final CollectionStore collectionStore;

    public CollectionService(CollectionStore collectionStore) {
        this.collectionStore = collectionStore;
    }

    // ==================== 集合 CRUD ====================

    /**
     * 获取所有集合。
     */
    public List<CollectionFile> getAllCollections() {
        return collectionStore.loadAll();
    }

    /**
     * 按 ID 获取集合。
     */
    public CollectionFile getCollection(String id) {
        return collectionStore.load(id);
    }

    /**
     * 创建新集合。
     */
    public CollectionFile createCollection(String name, String description) {
        CollectionFile collection = new CollectionFile(name);
        collection.setDescription(description);
        collectionStore.save(collection);
        return collection;
    }

    /**
     * 重命名集合。
     */
    public void renameCollection(String id, String newName) {
        CollectionFile collection = collectionStore.load(id);
        if (collection != null) {
            collection.setName(newName);
            collectionStore.save(collection);
        }
    }

    /**
     * 删除集合。
     */
    public void deleteCollection(String id) {
        collectionStore.delete(id);
    }

    /**
     * 保存集合 (持久化)。
     */
    public void saveCollection(CollectionFile collection) {
        if (collection != null) {
            collectionStore.save(collection);
        }
    }

    // ==================== 文件夹操作 ====================

    /**
     * 在集合根目录下创建文件夹。
     */
    public FolderNode createFolder(String collectionId, String parentId, String name) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            throw new IllegalArgumentException("集合不存在: " + collectionId);
        }
        FolderNode folder = new FolderNode(name);
        if (parentId == null) {
            collection.getItems().add(folder);
        } else {
            CollectionItem parent = collection.findItem(parentId);
            if (parent != null && parent.isFolder()) {
                parent.asFolder().addItem(folder);
            } else {
                collection.getItems().add(folder);
            }
        }
        collectionStore.save(collection);
        return folder;
    }

    /**
     * 重命名节点 (文件夹或请求)。
     */
    public void renameItem(String collectionId, String itemId, String newName) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            return;
        }
        CollectionItem item = collection.findItem(itemId);
        if (item != null) {
            item.setName(newName);
            collectionStore.save(collection);
        }
    }

    /**
     * 删除节点 (文件夹或请求)。
     */
    public void deleteItem(String collectionId, String itemId) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            return;
        }
        collection.removeItem(itemId);
        collectionStore.save(collection);
    }

    // ==================== 请求操作 ====================

    /**
     * 在集合中创建新请求。
     *
     * @param collectionId 集合 ID
     * @param parentId     父文件夹 ID, null 表示放在根目录
     * @param name         请求名称
     * @return 创建的请求节点
     */
    public RequestNode createRequest(String collectionId, String parentId, String name) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            throw new IllegalArgumentException("集合不存在: " + collectionId);
        }
        RequestNode request = new RequestNode(name);
        request.setMethod("GET");
        if (parentId == null) {
            collection.getItems().add(request);
        } else {
            CollectionItem parent = collection.findItem(parentId);
            if (parent != null && parent.isFolder()) {
                parent.asFolder().addItem(request);
            } else {
                collection.getItems().add(request);
            }
        }
        collectionStore.save(collection);
        return request;
    }

    /**
     * 更新请求节点。
     */
    public void updateRequest(String collectionId, RequestNode requestNode) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            return;
        }
        CollectionItem item = collection.findItem(requestNode.getId());
        if (item != null && item.isRequest()) {
            // 替换: 先删除旧的, 再在原位置插入新的
            FolderNode parent = collection.findParent(requestNode.getId());
            if (parent != null) {
                int index = parent.getItems().indexOf(item);
                parent.getItems().set(index, requestNode);
            } else {
                int index = collection.getItems().indexOf(item);
                collection.getItems().set(index, requestNode);
            }
            collectionStore.save(collection);
        }
    }

    /**
     * 按 ID 查找请求节点。
     */
    public RequestNode findRequest(String collectionId, String requestId) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            return null;
        }
        CollectionItem item = collection.findItem(requestId);
        return (item != null && item.isRequest()) ? item.asRequest() : null;
    }

    /**
     * 获取集合中所有请求 (深度优先遍历)。
     */
    public List<RequestNode> getAllRequests(String collectionId) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            return new ArrayList<>();
        }
        return collection.getAllRequests();
    }

    /**
     * 复制节点 (文件夹或请求)。
     */
    public CollectionItem duplicateItem(String collectionId, String itemId) {
        CollectionFile collection = collectionStore.load(collectionId);
        if (collection == null) {
            return null;
        }
        CollectionItem item = collection.findItem(itemId);
        if (item == null) {
            return null;
        }
        // 简单复制: 重命名后添加到同级
        CollectionItem copy = duplicateNode(item);
        if (copy != null) {
            copy.setName(item.getName() + " (副本)");
            FolderNode parent = collection.findParent(itemId);
            if (parent != null) {
                parent.addItem(copy);
            } else {
                collection.getItems().add(copy);
            }
            collectionStore.save(collection);
        }
        return copy;
    }

    private CollectionItem duplicateNode(CollectionItem item) {
        if (item.isRequest()) {
            RequestNode src = item.asRequest();
            RequestNode copy = new RequestNode(src.getName());
            copy.setMethod(src.getMethod());
            copy.setUrl(src.getUrl());
            copy.setDescription(src.getDescription());
            copy.setParams(new ArrayList<>(src.getParams()));
            copy.setHeaders(new ArrayList<>(src.getHeaders()));
            copy.setBodyType(src.getBodyType());
            copy.setBodyContent(src.getBodyContent());
            copy.setRawContentType(src.getRawContentType());
            copy.setAuth(src.getAuth());
            return copy;
        } else if (item.isFolder()) {
            FolderNode src = item.asFolder();
            FolderNode copy = new FolderNode(src.getName());
            copy.setDescription(src.getDescription());
            for (CollectionItem child : src.getItems()) {
                CollectionItem childCopy = duplicateNode(child);
                if (childCopy != null) {
                    copy.addItem(childCopy);
                }
            }
            return copy;
        }
        return null;
    }
}
