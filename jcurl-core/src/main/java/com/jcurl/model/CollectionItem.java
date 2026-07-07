package com.jcurl.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 集合树节点抽象基类 — 支持多态序列化。
 * <p>
 * 通过 {@code @JsonTypeInfo} + {@code @JsonSubTypes} 实现 JSON 多态:
 * Folder 与 Request 均可出现在集合树的 {@code items} 数组中,
 * 反序列化时根据 {@code type} 字段("folder"/"request")自动选择对应子类。
 * <p>
 * Folder 可嵌套 Folder,实现无限层级。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FolderNode.class, name = "folder"),
        @JsonSubTypes.Type(value = RequestNode.class, name = "request")
})
public abstract class CollectionItem {

    protected String id;
    protected String name;
    protected String description;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @JsonIgnore
    public boolean isFolder() { return false; }
    @JsonIgnore
    public boolean isRequest() { return false; }
}
