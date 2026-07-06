package com.jcurl.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * 集合树节点抽象基类 — 支持文件夹和请求两种节点类型。
 * <p>
 * 使用 Jackson 多态序列化: JSON 中通过 "type" 字段区分 "folder" 和 "request"。
 * 支持无限层级嵌套 (FolderNode 内部可包含 FolderNode)。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FolderNode.class, name = "folder"),
        @JsonSubTypes.Type(value = RequestNode.class, name = "request")
})
public abstract class CollectionItem {

    private String id;
    private String name;

    protected CollectionItem() {
        this.id = UUID.randomUUID().toString();
    }

    protected CollectionItem(String name) {
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

    /**
     * 判断是否为文件夹节点。
     */
    public boolean isFolder() {
        return this instanceof FolderNode;
    }

    /**
     * 判断是否为请求节点。
     */
    public boolean isRequest() {
        return this instanceof RequestNode;
    }

    /**
     * 转为 FolderNode (非文件夹时返回 null)。
     */
    public FolderNode asFolder() {
        return isFolder() ? (FolderNode) this : null;
    }

    /**
     * 转为 RequestNode (非请求时返回 null)。
     */
    public RequestNode asRequest() {
        return isRequest() ? (RequestNode) this : null;
    }
}
