package com.jcurl2.ui;

import com.jcurl2.model.Collection;
import com.jcurl2.model.CollectionItem;
import com.jcurl2.model.FolderNode;
import com.jcurl2.model.RequestNode;
import com.jcurl2.service.CollectionService;
import com.jcurl2.ui.event.RequestSelectionListener;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 集合树视图 — 左侧栏核心组件,展示所有集合的树形结构。
 * <p>
 * 功能:
 * <ul>
 *   <li>TreeView 展示 集合 → 文件夹(无限嵌套) → 请求 的树结构</li>
 *   <li>右键菜单: 新建集合/文件夹/请求、重命名、复制、删除</li>
 *   <li>双击请求节点: 触发 {@link RequestSelectionListener} 回调</li>
 *   <li>HTTP 方法颜色区分(GET 绿/POST 黄/PUT 蓝/DELETE 红/PATCH 紫)</li>
 *   <li>刷新后自动恢复展开与选中状态</li>
 * </ul>
 */
@Lazy
@Component
public class CollectionTreeView {

    private static final Logger log = LoggerFactory.getLogger(CollectionTreeView.class);

    private final CollectionService collectionService;

    /** 请求选中回调(由请求构建器设置) */
    private RequestSelectionListener selectionListener;

    /** TreeView 根节点(隐藏) */
    private TreeItem<TreeNodeData> rootItem;

    /** 集合树 TreeView */
    private TreeView<TreeNodeData> treeView;

    /** 记录刷新前展开的节点 ID,用于刷新后恢复 */
    private final Set<String> expandedIds = new HashSet<>();

    /** 记录刷新前选中的节点 ID,用于刷新后恢复 */
    private String selectedId;

    /** 拖拽中的源节点(用于视觉反馈与放置判定) */
    private TreeItem<TreeNodeData> draggedItem;

    public CollectionTreeView(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    /** 设置请求选中监听器 */
    public void setSelectionListener(RequestSelectionListener listener) {
        this.selectionListener = listener;
    }

    /** 构建集合树视图区域(标题栏 + TreeView) */
    public VBox buildView() {
        VBox container = new VBox(4);

        // 标题栏 + 新建集合按钮
        ToolBar header = new ToolBar();
        header.getStyleClass().add("collection-tree-header");
        Label title = new Label("集合");
        title.getStyleClass().add("sidebar-title");
        Button newCollectionBtn = new Button("+");
        newCollectionBtn.getStyleClass().add("icon-button");
        newCollectionBtn.setTooltip(new Tooltip("新建集合"));
        newCollectionBtn.setOnAction(e -> showNewCollectionDialog());
        header.getItems().addAll(title, new Separator(), newCollectionBtn);

        // TreeView
        rootItem = new TreeItem<>(new TreeNodeData(TreeNodeData.Type.ROOT, "root", null, null, null));
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new CollectionTreeCell());
        treeView.setContextMenu(buildEmptyContextMenu());

        // 选中事件 → 通知监听器
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.getValue() != null) {
                TreeNodeData data = selected.getValue();
                selectedId = data.getId();
                if (data.getType() == TreeNodeData.Type.REQUEST && selectionListener != null) {
                    selectionListener.onRequestSelected(data.getCollection(), (RequestNode) data.getItem());
                }
            }
        });

        // 双击事件
        treeView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<TreeNodeData> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null
                        && selected.getValue().getType() == TreeNodeData.Type.REQUEST
                        && selectionListener != null) {
                    TreeNodeData data = selected.getValue();
                    selectionListener.onRequestSelected(data.getCollection(), (RequestNode) data.getItem());
                }
            }
        });

        // 拖拽排序(Task: 拖拽排序)
        setupDragAndDrop();

        VBox.setVgrow(treeView, Priority.ALWAYS);
        container.getChildren().addAll(header, treeView);
        return container;
    }

    // ==================== 拖拽排序 ====================

    /**
     * 为 TreeView 启用拖拽排序。
     * <p>
     * 规则:
     * <ul>
     *   <li>请求节点可拖到文件夹或集合下</li>
     *   <li>文件夹可拖到其他文件夹或集合下(不能拖入自身或其子树)</li>
     *   <li>集合不能拖拽</li>
     *   <li>拖放完成后调用 {@link CollectionService#moveNode} 保存</li>
     *   <li>拖拽时源节点显示半透明效果</li>
     * </ul>
     */
    private void setupDragAndDrop() {
        // 1. 开始拖拽:仅请求/文件夹可拖,集合不可拖
        treeView.setOnDragDetected(e -> {
            TreeItem<TreeNodeData> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected == null || selected.getValue() == null) {
                return;
            }
            TreeNodeData data = selected.getValue();
            if (data.getType() == TreeNodeData.Type.COLLECTION || data.getType() == TreeNodeData.Type.ROOT) {
                return; // 集合/根节点不可拖拽
            }
            draggedItem = selected;
            Dragboard db = treeView.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(data.getId());
            db.setContent(content);
            // 视觉反馈:刷新单元格使源节点半透明
            treeView.refresh();
            e.consume();
        });

        // 2. 拖拽经过:判定目标是否可接受
        treeView.setOnDragOver(e -> {
            if (draggedItem == null || !e.getDragboard().hasString()) {
                return;
            }
            TreeItem<TreeNodeData> target = findTreeItemForDragEvent(e);
            if (target != null && canDrop(draggedItem, target)) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });

        // 3. 放置:执行移动并持久化
        treeView.setOnDragDropped(e -> {
            boolean success = false;
            if (draggedItem != null) {
                TreeItem<TreeNodeData> target = findTreeItemForDragEvent(e);
                if (target != null && canDrop(draggedItem, target)) {
                    success = performMove(draggedItem, target);
                }
            }
            e.setDropCompleted(success);
            e.consume();
        });

        // 4. 拖拽结束:恢复透明度并清理状态
        treeView.setOnDragDone(e -> {
            draggedItem = null;
            treeView.refresh();
            e.consume();
        });
    }

    /** 从拖拽事件中找到鼠标所在的 TreeItem(向上查找 TreeCell) */
    private TreeItem<TreeNodeData> findTreeItemForDragEvent(DragEvent event) {
        Node node = event.getPickResult().getIntersectedNode();
        while (node != null && !(node instanceof TreeCell)) {
            node = node.getParent();
        }
        if (node instanceof TreeCell) {
            TreeItem<?> item = ((TreeCell<?>) node).getTreeItem();
            if (item != null && item.getValue() instanceof TreeNodeData) {
                @SuppressWarnings("unchecked")
                TreeItem<TreeNodeData> typed = (TreeItem<TreeNodeData>) item;
                return typed;
            }
        }
        return null;
    }

    /** 判定源节点是否可放置到目标节点下 */
    private boolean canDrop(TreeItem<TreeNodeData> source, TreeItem<TreeNodeData> target) {
        if (source == null || target == null) return false;
        TreeNodeData sourceData = source.getValue();
        TreeNodeData targetData = target.getValue();
        if (sourceData == null || targetData == null) return false;

        // 目标必须是集合或文件夹(请求无子节点,不能作为放置目标)
        if (targetData.getType() != TreeNodeData.Type.COLLECTION
                && targetData.getType() != TreeNodeData.Type.FOLDER) {
            return false;
        }
        // 不能拖到自身
        if (sourceData.getId() != null && sourceData.getId().equals(targetData.getId())) {
            return false;
        }
        // 必须在同一集合内
        Collection srcCol = sourceData.getCollection();
        Collection tgtCol = targetData.getCollection();
        if (srcCol == null || tgtCol == null || !srcCol.getId().equals(tgtCol.getId())) {
            return false;
        }
        // 文件夹不能拖入自身或其子树(防止循环)
        if (sourceData.getType() == TreeNodeData.Type.FOLDER
                && isDescendantInTree(source, targetData.getId())) {
            return false;
        }
        return true;
    }

    /** 判断 targetId 是否为 ancestor 节点的后代(递归遍历 TreeItem 子树) */
    private boolean isDescendantInTree(TreeItem<TreeNodeData> ancestor, String targetId) {
        if (ancestor == null || targetId == null) return false;
        for (TreeItem<TreeNodeData> child : ancestor.getChildren()) {
            TreeNodeData cd = child.getValue();
            if (cd != null && targetId.equals(cd.getId())) {
                return true;
            }
            if (isDescendantInTree(child, targetId)) return true;
        }
        return false;
    }

    /** 执行节点移动并持久化(集合 → 根级别;文件夹 → 该文件夹内) */
    private boolean performMove(TreeItem<TreeNodeData> source, TreeItem<TreeNodeData> target) {
        TreeNodeData sourceData = source.getValue();
        TreeNodeData targetData = target.getValue();
        Collection collection = targetData.getCollection();
        if (collection == null) return false;

        String nodeId = sourceData.getId();
        String targetParentId;
        if (targetData.getType() == TreeNodeData.Type.COLLECTION) {
            targetParentId = null; // 放到集合根级别
        } else {
            targetParentId = targetData.getId(); // 放到该文件夹内
        }

        try {
            collectionService.moveNode(collection, nodeId, targetParentId, -1);
            refresh();
            return true;
        } catch (IllegalArgumentException ex) {
            log.warn("拖拽移动节点失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 从磁盘加载所有集合并刷新树 */
    public void refresh() {
        // 保存当前展开与选中状态
        saveExpansionState();

        rootItem.getChildren().clear();

        List<Collection> collections = collectionService.listCollections();
        for (Collection collection : collections) {
            TreeItem<TreeNodeData> collectionItem = buildCollectionNode(collection);
            rootItem.getChildren().add(collectionItem);
        }

        // 恢复展开与选中状态
        restoreExpansionState();
    }

    // ==================== 树构建 ====================

    private TreeItem<TreeNodeData> buildCollectionNode(Collection collection) {
        TreeItem<TreeNodeData> item = new TreeItem<>(
                new TreeNodeData(TreeNodeData.Type.COLLECTION, collection.getId(),
                        collection.getName(), collection, null));
        item.setExpanded(true);

        for (CollectionItem child : collection.getItems()) {
            item.getChildren().add(buildChildNode(child, collection));
        }
        return item;
    }

    private TreeItem<TreeNodeData> buildChildNode(CollectionItem node, Collection collection) {
        TreeNodeData data;
        if (node.isFolder()) {
            data = new TreeNodeData(TreeNodeData.Type.FOLDER, node.getId(),
                    node.getName(), collection, node);
        } else {
            data = new TreeNodeData(TreeNodeData.Type.REQUEST, node.getId(),
                    node.getName(), collection, node);
        }

        TreeItem<TreeNodeData> item = new TreeItem<>(data);
        item.setExpanded(expandedIds.contains(node.getId()));

        if (node.isFolder()) {
            for (CollectionItem child : ((FolderNode) node).getItems()) {
                item.getChildren().add(buildChildNode(child, collection));
            }
        }
        return item;
    }

    // ==================== 展开与选中状态恢复 ====================

    private void saveExpansionState() {
        expandedIds.clear();
        saveExpansionRecursive(rootItem);
    }

    private void saveExpansionRecursive(TreeItem<TreeNodeData> item) {
        if (item.isExpanded() && item.getValue() != null) {
            expandedIds.add(item.getValue().getId());
        }
        for (TreeItem<TreeNodeData> child : item.getChildren()) {
            saveExpansionRecursive(child);
        }
    }

    private void restoreExpansionState() {
        restoreExpansionRecursive(rootItem);
        if (selectedId != null) {
            findAndSelect(rootItem, selectedId);
        }
    }

    private void restoreExpansionRecursive(TreeItem<TreeNodeData> item) {
        if (item.getValue() != null && expandedIds.contains(item.getValue().getId())) {
            item.setExpanded(true);
        }
        for (TreeItem<TreeNodeData> child : item.getChildren()) {
            restoreExpansionRecursive(child);
        }
    }

    private void findAndSelect(TreeItem<TreeNodeData> item, String id) {
        if (item.getValue() != null && id.equals(item.getValue().getId())) {
            treeView.getSelectionModel().select(item);
            return;
        }
        for (TreeItem<TreeNodeData> child : item.getChildren()) {
            findAndSelect(child, id);
        }
    }

    // ==================== 右键菜单 ====================

    private ContextMenu buildEmptyContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem newCollection = new MenuItem("新建集合");
        newCollection.setOnAction(e -> showNewCollectionDialog());
        menu.getItems().add(newCollection);
        return menu;
    }

    private ContextMenu buildCollectionContextMenu(TreeNodeData data) {
        ContextMenu menu = new ContextMenu();

        MenuItem newFolder = new MenuItem("新建文件夹");
        newFolder.setOnAction(e -> showNewFolderDialog(data.getCollection(), data.getId()));

        MenuItem newRequest = new MenuItem("新建请求");
        newRequest.setOnAction(e -> showNewRequestDialog(data.getCollection(), data.getId()));

        MenuItem delete = new MenuItem("删除集合");
        delete.setStyle("-fx-text-fill: #f44336;");
        delete.setOnAction(e -> {
            collectionService.deleteCollection(data.getId());
            refresh();
        });

        menu.getItems().addAll(newFolder, newRequest, new SeparatorMenuItem(), delete);
        return menu;
    }

    private ContextMenu buildFolderContextMenu(TreeNodeData data) {
        ContextMenu menu = new ContextMenu();

        MenuItem newFolder = new MenuItem("新建子文件夹");
        newFolder.setOnAction(e -> showNewFolderDialog(data.getCollection(), data.getId()));

        MenuItem newRequest = new MenuItem("新建请求");
        newRequest.setOnAction(e -> showNewRequestDialog(data.getCollection(), data.getId()));

        MenuItem rename = new MenuItem("重命名");
        rename.setOnAction(e -> showRenameDialog(data.getCollection(), data.getId(), data.getName()));

        MenuItem delete = new MenuItem("删除");
        delete.setStyle("-fx-text-fill: #f44336;");
        delete.setOnAction(e -> {
            collectionService.deleteNode(data.getCollection(), data.getId());
            refresh();
        });

        menu.getItems().addAll(newFolder, newRequest, new SeparatorMenuItem(), rename, delete);
        return menu;
    }

    private ContextMenu buildRequestContextMenu(TreeNodeData data) {
        ContextMenu menu = new ContextMenu();

        MenuItem rename = new MenuItem("重命名");
        rename.setOnAction(e -> showRenameDialog(data.getCollection(), data.getId(), data.getName()));

        MenuItem duplicate = new MenuItem("复制");
        duplicate.setOnAction(e -> {
            collectionService.duplicateRequest(data.getCollection(), data.getId());
            refresh();
        });

        MenuItem delete = new MenuItem("删除");
        delete.setStyle("-fx-text-fill: #f44336;");
        delete.setOnAction(e -> {
            collectionService.deleteNode(data.getCollection(), data.getId());
            refresh();
        });

        menu.getItems().addAll(rename, duplicate, new SeparatorMenuItem(), delete);
        return menu;
    }

    // ==================== 对话框 ====================

    private void showNewCollectionDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建集合");
        dialog.setHeaderText(null);
        dialog.setContentText("集合名称:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                collectionService.createCollection(name.trim());
                refresh();
            }
        });
    }

    private void showNewFolderDialog(Collection collection, String parentId) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建文件夹");
        dialog.setHeaderText(null);
        dialog.setContentText("文件夹名称:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                collectionService.addFolder(collection, parentId, name.trim());
                refresh();
            }
        });
    }

    private void showNewRequestDialog(Collection collection, String parentId) {
        Dialog<NewRequestData> dialog = new Dialog<>();
        dialog.setTitle("新建请求");
        dialog.setHeaderText(null);

        // 表单
        TextField nameField = new TextField();
        nameField.setPromptText("请求名称");
        ComboBox<String> methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
        methodCombo.setValue("GET");
        TextField urlField = new TextField();
        urlField.setPromptText("请求 URL (如 /api/users)");

        VBox form = new VBox(8);
        form.getChildren().addAll(
                new Label("名称:"), nameField,
                new Label("方法:"), methodCombo,
                new Label("URL:"), urlField);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                String name = nameField.getText().isBlank() ? "新请求" : nameField.getText().trim();
                return new NewRequestData(name, methodCombo.getValue(), urlField.getText().trim());
            }
            return null;
        });

        Optional<NewRequestData> result = dialog.showAndWait();
        result.ifPresent(data -> {
            collectionService.addRequest(collection, parentId, data.name, data.method, data.url);
            refresh();
        });
    }

    private void showRenameDialog(Collection collection, String nodeId, String currentName) {
        TextInputDialog dialog = new TextInputDialog(currentName);
        dialog.setTitle("重命名");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                collectionService.renameNode(collection, nodeId, name.trim());
                refresh();
            }
        });
    }

    // ==================== 内部类 ====================

    /** 树节点数据包装类 */
    static class TreeNodeData {
        enum Type { ROOT, COLLECTION, FOLDER, REQUEST }

        private final Type type;
        private final String id;
        private final String name;
        private final Collection collection;
        private final CollectionItem item;

        TreeNodeData(Type type, String id, String name, Collection collection, CollectionItem item) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.collection = collection;
            this.item = item;
        }

        public Type getType() { return type; }
        public String getId() { return id; }
        public String getName() { return name; }
        public Collection getCollection() { return collection; }
        public CollectionItem getItem() { return item; }
    }

    /** 新建请求数据 */
    private static class NewRequestData {
        final String name;
        final String method;
        final String url;

        NewRequestData(String name, String method, String url) {
            this.name = name;
            this.method = method;
            this.url = url;
        }
    }

    /** 自定义 TreeCell — 显示图标 + HTTP 方法颜色 + 右键菜单 */
    private class CollectionTreeCell extends TreeCell<TreeNodeData> {

        @Override
        protected void updateItem(TreeNodeData item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setStyle(null);
                setOpacity(1.0);
                return;
            }

            // 拖拽视觉反馈:被拖拽的源节点半透明
            setOpacity(draggedItem != null && draggedItem == getTreeItem() ? 0.4 : 1.0);

            setContextMenu(null);

            switch (item.getType()) {
                case COLLECTION:
                    setText("📦 " + item.getName());
                    setContextMenu(buildCollectionContextMenu(item));
                    break;
                case FOLDER:
                    setText("📁 " + item.getName());
                    setContextMenu(buildFolderContextMenu(item));
                    break;
                case REQUEST:
                    RequestNode req = (RequestNode) item.getItem();
                    String method = req.getMethod() != null ? req.getMethod() : "GET";
                    setText("[" + method + "] " + item.getName());
                    setStyle(getMethodColor(method));
                    setContextMenu(buildRequestContextMenu(item));
                    break;
                default:
                    setText(item.getName());
            }
        }

        private String getMethodColor(String method) {
            return switch (method.toUpperCase()) {
                case "GET" -> "-fx-text-fill: #4caf50;";       // 绿色
                case "POST" -> "-fx-text-fill: #ffc107;";      // 黄色
                case "PUT" -> "-fx-text-fill: #2196f3;";       // 蓝色
                case "DELETE" -> "-fx-text-fill: #f44336;";    // 红色
                case "PATCH" -> "-fx-text-fill: #9c27b0;";     // 紫色
                default -> "-fx-text-fill: #9e9e9e;";          // 灰色
            };
        }
    }
}
