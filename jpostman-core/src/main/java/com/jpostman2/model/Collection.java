package com.jpostman2.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jpostman2.model.component.AuthConfig;
import com.jpostman2.model.component.Header;
import com.jpostman2.model.component.Variable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 集合(Collection)— 顶级容器,对应磁盘上一个独立 JSON 文件。
 * <p>
 * 包含:
 * <ul>
 *   <li>集合级继承配置: baseUrl / auth / headers(下级请求自动继承)</li>
 *   <li>集合变量: 该集合下所有请求共享</li>
 *   <li>items: Folder/Request 组成的树(Folder 无限嵌套)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Collection {

    private String id;
    private String name;
    private String description;

    /** 集合级公共前置 URL,如 https://api.example.com/v1 */
    private String baseUrl;

    /** 集合级公共认证(请求 auth.type=inherit 时继承) */
    private AuthConfig auth;

    /** 集合级公共 Headers(下级请求自动携带) */
    private List<Header> headers = new ArrayList<>();

    /** 集合变量(该集合下所有请求共享) */
    private List<Variable> variables = new ArrayList<>();

    /** 集合树根节点列表(Folder/Request) */
    private List<CollectionItem> items = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Collection() {}

    public Collection(String id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    public List<Header> getHeaders() {
        if (headers == null) headers = new ArrayList<>();
        return headers;
    }
    public void setHeaders(List<Header> headers) { this.headers = headers; }
    public List<Variable> getVariables() {
        if (variables == null) variables = new ArrayList<>();
        return variables;
    }
    public void setVariables(List<Variable> variables) { this.variables = variables; }
    public List<CollectionItem> getItems() {
        if (items == null) items = new ArrayList<>();
        return items;
    }
    public void setItems(List<CollectionItem> items) { this.items = items; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 递归查找指定 ID 的节点(Folder 或 Request)。
     *
     * @param nodeId 节点 ID
     * @return 找到的节点,未找到返回 null
     */
    public CollectionItem findNode(String nodeId) {
        return findNodeInList(items, nodeId);
    }

    private CollectionItem findNodeInList(List<CollectionItem> itemList, String nodeId) {
        for (CollectionItem item : itemList) {
            if (nodeId.equals(item.getId())) {
                return item;
            }
            if (item.isFolder()) {
                CollectionItem found = findNodeInList(((FolderNode) item).getItems(), nodeId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 递归提取集合中所有 Request(扁平化)。
     */
    @JsonIgnore
    public List<RequestNode> getAllRequests() {
        List<RequestNode> result = new ArrayList<>();
        collectRequests(items, result);
        return result;
    }

    private void collectRequests(List<CollectionItem> itemList, List<RequestNode> result) {
        for (CollectionItem item : itemList) {
            if (item.isRequest()) {
                result.add((RequestNode) item);
            } else if (item.isFolder()) {
                collectRequests(((FolderNode) item).getItems(), result);
            }
        }
    }
}
