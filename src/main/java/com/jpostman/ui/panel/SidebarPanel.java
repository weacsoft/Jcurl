package com.jpostman.ui.panel;

import com.jpostman.model.CollectionFile;
import com.jpostman.model.CollectionItem;
import com.jpostman.model.FolderNode;
import com.jpostman.model.HistoryRecord;
import com.jpostman.model.RequestNode;
import com.jpostman.service.CollectionService;
import com.jpostman.service.HistoryService;
import org.springframework.stereotype.Component;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 左侧边栏 — v2 架构。
 * <p>
 * 使用 JTabbedPane 顶部切换两个标签页:
 * <ul>
 *   <li>集合: 树形结构 (隐藏根) CollectionFile -> FolderNode/RequestNode (递归嵌套)</li>
 *   <li>历史: JList&lt;HistoryRecord&gt;, 显示 "[GET] /api/users - 200 OK - 2.3ms", 支持 URL 关键字搜索</li>
 * </ul>
 * <p>
 * 集合树支持右键菜单, 根据节点类型提供新建/重命名/删除/复制操作。
 * <p>
 * 通过回调接口通知外部选中事件:
 * <ul>
 *   <li>{@link #setOnCollectionSelected(Consumer)}: 选中集合 (或集合下任意节点时一并触发, 携带所属集合)</li>
 *   <li>{@link #setOnRequestSelected(Consumer)}: 选中 RequestNode 时触发</li>
 *   <li>{@link #setOnHistorySelected(Consumer)}: 选中历史记录时触发</li>
 *   <li>{@link #setOnTreeModified(Runnable)}: 树结构修改后触发</li>
 * </ul>
 */
@Component
public class SidebarPanel extends JPanel {

    private final transient CollectionService collectionService;
    private final transient HistoryService historyService;

    private final DefaultMutableTreeNode treeRoot;
    private final DefaultTreeModel treeModel;
    private final JTree tree;

    private final DefaultListModel<HistoryRecord> historyListModel;
    private final JList<HistoryRecord> historyList;
    private final JTextField historySearchField;

    /** collectionId -> CollectionFile, 用于节点选中时查找所属集合 */
    private final Map<String, CollectionFile> collectionMap = new LinkedHashMap<>();

    private transient Consumer<CollectionFile> onCollectionSelected;
    private transient Consumer<RequestNode> onRequestSelected;
    private transient Consumer<HistoryRecord> onHistorySelected;
    private transient Runnable onTreeModified;

    public SidebarPanel(CollectionService collectionService, HistoryService historyService) {
        this.collectionService = collectionService;
        this.historyService = historyService;

        setLayout(new BorderLayout());

        JTabbedPane tabbedPane = new JTabbedPane();

        // ===== 集合 标签页 =====
        treeRoot = new DefaultMutableTreeNode("集合");
        treeModel = new DefaultTreeModel(treeRoot);
        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new CollectionTreeCellRenderer());
        tree.addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                handleTreeSelection();
            }
        });
        tree.addMouseListener(new TreeMouseListener());
        JScrollPane treeScrollPane = new JScrollPane(tree);
        tabbedPane.addTab("集合", treeScrollPane);

        // ===== 历史 标签页 =====
        JPanel historyPanel = new JPanel(new BorderLayout());

        JPanel searchPanel = new JPanel(new BorderLayout());
        historySearchField = new JTextField();
        JButton searchButton = new JButton("搜索");
        searchPanel.add(historySearchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);
        historyPanel.add(searchPanel, BorderLayout.NORTH);

        historyListModel = new DefaultListModel<>();
        historyList = new JList<>(historyListModel);
        historyList.setCellRenderer(new HistoryListCellRenderer());
        historyList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            HistoryRecord selected = historyList.getSelectedValue();
            if (selected != null && onHistorySelected != null) {
                onHistorySelected.accept(selected);
            }
        });
        JScrollPane historyScrollPane = new JScrollPane(historyList);
        historyPanel.add(historyScrollPane, BorderLayout.CENTER);

        searchButton.addActionListener(e -> searchHistory());
        historySearchField.addActionListener(e -> searchHistory());

        tabbedPane.addTab("历史", historyPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    // ==================== 回调设置 ====================

    /**
     * 设置集合选中回调。选中集合节点, 或选中集合下任意子节点时都会触发
     * (子节点选中时携带其所属集合, 便于外部保存请求时获取 collectionId)。
     */
    public void setOnCollectionSelected(Consumer<CollectionFile> callback) {
        this.onCollectionSelected = callback;
    }

    public void setOnRequestSelected(Consumer<RequestNode> callback) {
        this.onRequestSelected = callback;
    }

    public void setOnHistorySelected(Consumer<HistoryRecord> callback) {
        this.onHistorySelected = callback;
    }

    public void setOnTreeModified(Runnable callback) {
        this.onTreeModified = callback;
    }

    // ==================== 树选择 ====================

    /**
     * 树选择事件处理:
     * - 集合节点: 触发 onCollectionSelected
     * - 文件夹/请求节点: 先触发 onCollectionSelected (所属集合), 请求节点再触发 onRequestSelected
     */
    private void handleTreeSelection() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null) {
            return;
        }
        Object userObject = node.getUserObject();
        if (!(userObject instanceof TreeUserObject)) {
            return;
        }
        TreeUserObject tuo = (TreeUserObject) userObject;

        if (tuo.collection != null) {
            if (onCollectionSelected != null) {
                onCollectionSelected.accept(tuo.collection);
            }
        } else if (tuo.item != null) {
            // 通知所属集合, 外部据此维护 currentCollectionId
            CollectionFile owner = collectionMap.get(tuo.collectionId);
            if (owner != null && onCollectionSelected != null) {
                onCollectionSelected.accept(owner);
            }
            if (tuo.item.isRequest() && onRequestSelected != null) {
                onRequestSelected.accept(tuo.item.asRequest());
            }
        }
    }

    // ==================== 数据刷新 ====================

    /**
     * 从 CollectionService.getAllCollections() 重新加载集合树。
     * 在 EDT 中执行。递归构建 CollectionFile -> FolderNode/RequestNode 嵌套结构。
     */
    public void refreshTree() {
        SwingUtilities.invokeLater(() -> {
            treeRoot.removeAllChildren();
            collectionMap.clear();

            List<CollectionFile> collections = collectionService.getAllCollections();
            if (collections != null) {
                for (CollectionFile collection : collections) {
                    collectionMap.put(collection.getId(), collection);
                    DefaultMutableTreeNode collectionNode =
                            new DefaultMutableTreeNode(new TreeUserObject(collection));
                    addChildren(collectionNode, collection.getId(), collection.getItems());
                    treeRoot.add(collectionNode);
                }
            }

            treeModel.reload();
            // 展开所有节点
            for (int i = 0; i < tree.getRowCount(); i++) {
                tree.expandRow(i);
            }
        });
    }

    /**
     * 递归添加子节点 (文件夹/请求)。
     */
    private void addChildren(DefaultMutableTreeNode parent, String collectionId, List<CollectionItem> items) {
        if (items == null) {
            return;
        }
        for (CollectionItem item : items) {
            DefaultMutableTreeNode childNode =
                    new DefaultMutableTreeNode(new TreeUserObject(collectionId, item));
            parent.add(childNode);
            if (item.isFolder()) {
                addChildren(childNode, collectionId, item.asFolder().getItems());
            }
        }
    }

    /**
     * 从 HistoryService.getAllHistory() 重新加载历史列表。
     */
    public void refreshHistory() {
        SwingUtilities.invokeLater(() -> {
            historyListModel.clear();
            List<HistoryRecord> all = historyService.getAllHistory();
            if (all != null) {
                for (HistoryRecord h : all) {
                    historyListModel.addElement(h);
                }
            }
        });
    }

    /**
     * 按关键字搜索历史。搜索框为空时显示全部, 否则按 URL 模糊搜索。
     */
    private void searchHistory() {
        SwingUtilities.invokeLater(() -> {
            historyListModel.clear();
            String keyword = historySearchField.getText();
            List<HistoryRecord> results;
            if (keyword == null || keyword.trim().isEmpty()) {
                results = historyService.getAllHistory();
            } else {
                results = historyService.search(keyword.trim(), null);
            }
            if (results != null) {
                for (HistoryRecord h : results) {
                    historyListModel.addElement(h);
                }
            }
        });
    }

    /**
     * 通知外部树结构已修改。
     */
    private void notifyTreeModified() {
        if (onTreeModified != null) {
            onTreeModified.run();
        }
    }

    // ==================== 右键菜单 ====================

    /**
     * 显示右键菜单。根据节点类型构建不同菜单项。
     *
     * @param node 点击的节点, null 表示空白区域或根节点
     * @param x    屏幕 x 坐标
     * @param y    屏幕 y 坐标
     */
    private void showTreePopupMenu(DefaultMutableTreeNode node, int x, int y) {
        TreeUserObject tuo = (node != null && node.getUserObject() instanceof TreeUserObject)
                ? (TreeUserObject) node.getUserObject() : null;
        JPopupMenu popup = new JPopupMenu();

        if (tuo == null) {
            // 空白区域或根节点
            popup.add(createMenuItem("新建集合", () -> createCollection()));
        } else if (tuo.collection != null) {
            // 集合节点
            popup.add(createMenuItem("新建文件夹", () -> createFolder(tuo.collectionId, null)));
            popup.add(createMenuItem("新建请求", () -> createRequest(tuo.collectionId, null)));
            popup.addSeparator();
            popup.add(createMenuItem("重命名", () -> renameCollection(tuo.collection)));
            popup.add(createMenuItem("删除", () -> deleteCollection(tuo.collection)));
            popup.add(createMenuItem("复制", () -> duplicateCollection(tuo.collection)));
        } else if (tuo.item.isFolder()) {
            // 文件夹节点
            popup.add(createMenuItem("新建文件夹", () -> createFolder(tuo.collectionId, tuo.item.getId())));
            popup.add(createMenuItem("新建请求", () -> createRequest(tuo.collectionId, tuo.item.getId())));
            popup.addSeparator();
            popup.add(createMenuItem("重命名", () -> renameItem(tuo.collectionId, tuo.item)));
            popup.add(createMenuItem("删除", () -> deleteItem(tuo.collectionId, tuo.item)));
            popup.add(createMenuItem("复制", () -> duplicateItem(tuo.collectionId, tuo.item)));
        } else if (tuo.item.isRequest()) {
            // 请求节点
            popup.add(createMenuItem("重命名", () -> renameItem(tuo.collectionId, tuo.item)));
            popup.add(createMenuItem("删除", () -> deleteItem(tuo.collectionId, tuo.item)));
            popup.add(createMenuItem("复制", () -> duplicateItem(tuo.collectionId, tuo.item)));
        }

        popup.show(tree, x, y);
    }

    private JMenuItem createMenuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    // ===== 集合操作 =====

    private void createCollection() {
        String name = JOptionPane.showInputDialog(this, "输入集合名称:", "新建集合",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            collectionService.createCollection(name.trim(), null);
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "创建集合失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void renameCollection(CollectionFile collection) {
        String name = (String) JOptionPane.showInputDialog(this, "输入新的集合名称:", "重命名集合",
                JOptionPane.PLAIN_MESSAGE, null, null, collection.getName());
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            collectionService.renameCollection(collection.getId(), name.trim());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "重命名集合失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteCollection(CollectionFile collection) {
        int confirm = JOptionPane.showConfirmDialog(this,
                "删除集合 \"" + collection.getName() + "\" 及其所有文件夹和请求？",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            collectionService.deleteCollection(collection.getId());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "删除集合失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 复制集合: 新建一个同名 (副本) 的集合, 深拷贝所有配置和子节点 (重新生成 ID)。
     */
    private void duplicateCollection(CollectionFile original) {
        try {
            CollectionFile copy = collectionService.createCollection(
                    original.getName() + " (副本)", original.getDescription());
            copy.setBaseUrl(original.getBaseUrl());
            copy.setHeaders(cloneItemsAsKeyValues(original.getHeaders()));
            copy.setVariables(new ArrayList<>(original.getVariables()));
            copy.setAuth(original.getAuth());
            List<CollectionItem> clonedItems = new ArrayList<>();
            if (original.getItems() != null) {
                for (CollectionItem item : original.getItems()) {
                    CollectionItem cloned = cloneItemTree(item);
                    if (cloned != null) {
                        clonedItems.add(cloned);
                    }
                }
            }
            copy.setItems(clonedItems);
            collectionService.saveCollection(copy);
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "复制集合失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ===== 文件夹/请求操作 =====

    private void createFolder(String collectionId, String parentId) {
        String name = JOptionPane.showInputDialog(this, "输入文件夹名称:", "新建文件夹",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            collectionService.createFolder(collectionId, parentId, name.trim());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "创建文件夹失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createRequest(String collectionId, String parentId) {
        String name = JOptionPane.showInputDialog(this, "输入请求名称:", "新建请求",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            collectionService.createRequest(collectionId, parentId, name.trim());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "创建请求失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void renameItem(String collectionId, CollectionItem item) {
        String name = (String) JOptionPane.showInputDialog(this, "输入新的名称:", "重命名",
                JOptionPane.PLAIN_MESSAGE, null, null, item.getName());
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            collectionService.renameItem(collectionId, item.getId(), name.trim());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "重命名失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteItem(String collectionId, CollectionItem item) {
        int confirm = JOptionPane.showConfirmDialog(this,
                "删除 \"" + item.getName() + "\"？",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            collectionService.deleteItem(collectionId, item.getId());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "删除失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void duplicateItem(String collectionId, CollectionItem item) {
        try {
            collectionService.duplicateItem(collectionId, item.getId());
            refreshTree();
            notifyTreeModified();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "复制失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ===== 深拷贝辅助 (用于复制集合, 重新生成 ID) =====

    private CollectionItem cloneItemTree(CollectionItem item) {
        if (item == null) {
            return null;
        }
        if (item.isRequest()) {
            RequestNode src = item.asRequest();
            RequestNode copy = new RequestNode(src.getName());
            copy.setMethod(src.getMethod());
            copy.setUrl(src.getUrl());
            copy.setDescription(src.getDescription());
            copy.setParams(cloneItemsAsKeyValues(src.getParams()));
            copy.setHeaders(cloneItemsAsKeyValues(src.getHeaders()));
            copy.setBodyType(src.getBodyType());
            copy.setBodyContent(src.getBodyContent());
            copy.setRawContentType(src.getRawContentType());
            copy.setAuth(src.getAuth());
            return copy;
        }
        if (item.isFolder()) {
            FolderNode src = item.asFolder();
            FolderNode copy = new FolderNode(src.getName());
            copy.setDescription(src.getDescription());
            if (src.getItems() != null) {
                for (CollectionItem child : src.getItems()) {
                    CollectionItem childCopy = cloneItemTree(child);
                    if (childCopy != null) {
                        copy.addItem(childCopy);
                    }
                }
            }
            return copy;
        }
        return null;
    }

    private List<com.jpostman.model.KeyValue> cloneItemsAsKeyValues(
            List<com.jpostman.model.KeyValue> kvs) {
        List<com.jpostman.model.KeyValue> copy = new ArrayList<>();
        if (kvs != null) {
            for (com.jpostman.model.KeyValue kv : kvs) {
                copy.add(new com.jpostman.model.KeyValue(
                        kv.getKey(), kv.getValue(), kv.getDescription(), kv.isEnabled()));
            }
        }
        return copy;
    }

    // ==================== 内部类 ====================

    /**
     * 树节点 userObject 包装, 同时携带所属集合 ID。
     * 集合节点 collection 非空; 文件夹/请求节点 item 非空。
     */
    private static class TreeUserObject {
        final String collectionId;
        final CollectionItem item;
        final CollectionFile collection;
        final String displayName;

        TreeUserObject(CollectionFile collection) {
            this.collectionId = collection.getId();
            this.collection = collection;
            this.item = null;
            this.displayName = collection.getName();
        }

        TreeUserObject(String collectionId, CollectionItem item) {
            this.collectionId = collectionId;
            this.item = item;
            this.collection = null;
            this.displayName = item.getName();
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * 树鼠标监听器 — 处理右键弹出菜单。
     */
    private class TreeMouseListener extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent e) {
            maybeShowPopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybeShowPopup(e);
        }

        private void maybeShowPopup(MouseEvent e) {
            if (!e.isPopupTrigger()) {
                return;
            }
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            DefaultMutableTreeNode node = null;
            if (path != null) {
                node = (DefaultMutableTreeNode) path.getLastPathComponent();
                tree.setSelectionPath(path);
            } else {
                tree.clearSelection();
            }
            // 根节点或空白区域时按根节点处理
            if (node != null && node == treeRoot) {
                node = null;
            }
            showTreePopupMenu(node, e.getX(), e.getY());
        }
    }

    /**
     * 集合树单元格渲染器:
     * - 集合节点: 显示名称
     * - 文件夹节点: 显示名称
     * - 请求节点: 显示 "[METHOD] name"
     */
    private static class CollectionTreeCellRenderer extends DefaultTreeCellRenderer {

        @Override
        public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode) {
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof TreeUserObject) {
                    TreeUserObject tuo = (TreeUserObject) userObject;
                    if (tuo.collection != null) {
                        setText(tuo.collection.getName());
                        setIcon(getDefaultClosedIcon());
                    } else if (tuo.item != null) {
                        if (tuo.item.isFolder()) {
                            setText(tuo.item.getName());
                            setIcon(expanded ? getDefaultOpenIcon() : getDefaultClosedIcon());
                        } else {
                            RequestNode req = tuo.item.asRequest();
                            String method = req.getMethod() != null ? req.getMethod() : "GET";
                            setText("[" + method + "] " + tuo.item.getName());
                            setIcon(getDefaultLeafIcon());
                        }
                    }
                }
            }
            return this;
        }
    }

    /**
     * 历史列表单元格渲染器 — 显示 "[GET] /api/users - 200 OK - 2.3ms"。
     */
    private static class HistoryListCellRenderer extends DefaultListCellRenderer {

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof HistoryRecord) {
                HistoryRecord h = (HistoryRecord) value;
                String method = h.getMethod() != null ? h.getMethod() : "?";
                String url = h.getUrl() != null ? h.getUrl() : "";
                String status = h.getStatusCode() > 0 ? String.valueOf(h.getStatusCode()) : "--";
                String statusText = (h.getStatusText() != null && !h.getStatusText().isEmpty())
                        ? " " + h.getStatusText() : "";
                String time = h.getResponseTime() >= 0 ? String.valueOf(h.getResponseTime()) : "--";
                setText("[" + method + "] " + url + " - " + status + statusText + " - " + time + "ms");
            }
            return this;
        }
    }
}
