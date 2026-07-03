package com.jpostman.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹节点 — 集合树中的目录节点, 支持无限层级嵌套。
 * <p>
 * 可包含子文件夹 (FolderNode) 和请求 (RequestNode)。
 */
public class FolderNode extends CollectionItem {

    private String description;
    private List<CollectionItem> items = new ArrayList<>();

    public FolderNode() {
        super();
    }

    public FolderNode(String name) {
        super(name);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<CollectionItem> getItems() {
        return items;
    }

    public void setItems(List<CollectionItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    /**
     * 添加子节点。
     */
    public void addItem(CollectionItem item) {
        items.add(item);
    }

    /**
     * 在指定位置插入子节点。
     */
    public void addItem(int index, CollectionItem item) {
        items.add(index, item);
    }

    /**
     * 移除子节点。
     */
    public void removeItem(CollectionItem item) {
        items.remove(item);
    }

    /**
     * 按 ID 查找子节点。
     */
    public CollectionItem findChild(String id) {
        for (CollectionItem item : items) {
            if (item.getId().equals(id)) {
                return item;
            }
            if (item.isFolder()) {
                CollectionItem found = item.asFolder().findChild(id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 按 ID 移除子节点 (递归)。
     */
    public boolean removeChild(String id) {
        for (int i = 0; i < items.size(); i++) {
            CollectionItem item = items.get(i);
            if (item.getId().equals(id)) {
                items.remove(i);
                return true;
            }
            if (item.isFolder() && item.asFolder().removeChild(id)) {
                return true;
            }
        }
        return false;
    }
}
