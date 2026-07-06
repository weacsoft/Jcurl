package com.jcurl.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 集合文件 — 对应磁盘上的一个 JSON 文件 (如 collections/订单服务.json)。
 * <p>
 * 顶级容器, 包含树形结构的文件夹和请求, 以及集合级别的继承配置:
 * baseUrl, 公共 headers, 公共 auth, 集合变量。
 */
public class CollectionFile {

    private String id;
    private String name;
    private String description;

    /** 集合级公共前置 URL, 请求中的相对路径自动拼接 */
    private String baseUrl;

    /** 集合级公共 headers, 所有请求自动携带 */
    private List<KeyValue> headers = new ArrayList<>();

    /** 集合级公共认证, 所有请求默认使用 (请求可单独覆盖) */
    private AuthConfig auth = new AuthConfig();

    /** 集合级变量, 该集合下所有请求共享 */
    private List<Variable> variables = new ArrayList<>();

    /** 树形结构: 文件夹和请求的嵌套列表 */
    private List<CollectionItem> items = new ArrayList<>();

    public CollectionFile() {
        this.id = UUID.randomUUID().toString();
    }

    public CollectionFile(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<KeyValue> getHeaders() {
        return headers;
    }

    public void setHeaders(List<KeyValue> headers) {
        this.headers = headers != null ? headers : new ArrayList<>();
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth != null ? auth : new AuthConfig();
    }

    public List<Variable> getVariables() {
        return variables;
    }

    public void setVariables(List<Variable> variables) {
        this.variables = variables != null ? variables : new ArrayList<>();
    }

    public List<CollectionItem> getItems() {
        return items;
    }

    public void setItems(List<CollectionItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    /**
     * 递归查找指定 ID 的节点 (文件夹或请求)。
     */
    public CollectionItem findItem(String itemId) {
        return findInList(items, itemId);
    }

    /**
     * 递归查找节点所属的父 FolderNode (顶级节点返回 null)。
     */
    public FolderNode findParent(String itemId) {
        return findParentInList(items, itemId);
    }

    private CollectionItem findInList(List<CollectionItem> list, String itemId) {
        for (CollectionItem item : list) {
            if (item.getId().equals(itemId)) {
                return item;
            }
            if (item.isFolder()) {
                CollectionItem found = findInList(item.asFolder().getItems(), itemId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private FolderNode findParentInList(List<CollectionItem> list, String itemId) {
        for (CollectionItem item : list) {
            if (item.getId().equals(itemId)) {
                return null; // 顶级节点, 无父文件夹
            }
            if (item.isFolder()) {
                FolderNode folder = item.asFolder();
                for (CollectionItem child : folder.getItems()) {
                    if (child.getId().equals(itemId)) {
                        return folder;
                    }
                }
                FolderNode found = findParentInList(folder.getItems(), itemId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 递归删除指定 ID 的节点。
     */
    public boolean removeItem(String itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(itemId)) {
                items.remove(i);
                return true;
            }
            if (items.get(i).isFolder() && items.get(i).asFolder().removeChild(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集树中所有请求节点 (深度优先)。
     */
    public List<RequestNode> getAllRequests() {
        List<RequestNode> result = new ArrayList<>();
        collectRequests(items, result);
        return result;
    }

    private void collectRequests(List<CollectionItem> list, List<RequestNode> result) {
        for (CollectionItem item : list) {
            if (item.isRequest()) {
                result.add(item.asRequest());
            } else if (item.isFolder()) {
                collectRequests(item.asFolder().getItems(), result);
            }
        }
    }
}
