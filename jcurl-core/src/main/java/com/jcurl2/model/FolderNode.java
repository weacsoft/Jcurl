package com.jcurl2.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹节点 — 集合树的容器节点,支持无限层级嵌套。
 * <p>
 * {@code items} 可包含 FolderNode 和 RequestNode,递归实现任意深度层级。
 */
public class FolderNode extends CollectionItem {

    private List<CollectionItem> items = new ArrayList<>();

    public FolderNode() {
        // Jackson 反序列化需要
    }

    public FolderNode(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public List<CollectionItem> getItems() {
        if (items == null) {
            items = new ArrayList<>();
        }
        return items;
    }

    public void setItems(List<CollectionItem> items) {
        this.items = items;
    }

    @Override
    public boolean isFolder() {
        return true;
    }
}
