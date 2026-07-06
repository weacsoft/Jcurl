package com.jcurl2.ui;

import com.jcurl2.plugin.Plugin;
import com.jcurl2.plugin.PluginService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 插件管理对话框 — 以表格展示已加载插件, 支持安装 / 卸载 / 重载 / 刷新。
 * <p>
 * 数据来源: {@link PluginService#listPlugins()} 返回当前已加载的插件元数据列表。
 * <p>
 * 关键约束 (兼容 Windows Server 环境):
 * <ul>
 *   <li>选择插件文件使用 {@link java.awt.FileDialog} (而非 JFileChooser / JavaFX FileChooser),
 *       避免 Swing 文件选择器在 Windows Server 无桌面会话时卡死。</li>
 *   <li>安装 / 卸载 / 重载操作均在后台线程执行 (使用 {@link javafx.concurrent.Task}),
 *       防止编译 / IO 阻塞 JavaFX UI 线程。操作期间显示进度提示并禁用按钮。</li>
 * </ul>
 * <p>
 * 布局:
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │ [安装插件][卸载选中][重载选中][重载全部][刷新]          │ ← 工具栏
 * ├──────────────────────────────────────────────────────┤
 * │ Name │ Version │ Status │ Description                 │
 * │  ... │   ...   │  ...   │   ...                       │
 * ├──────────────────────────────────────────────────────┤
 * │ ◌ 正在安装插件: xxx.java ...    (进度提示)             │ ← 状态栏
 * └──────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 暗色主题样式: 根容器使用 CSS class {@code plugin-dialog}, 表格使用 {@code env-table},
 * 按钮使用 {@code toolbar-button}。
 */
@Lazy
@Component
public class PluginManagerDialog {

    private static final Logger log = LoggerFactory.getLogger(PluginManagerDialog.class);

    private final PluginService pluginService;

    public PluginManagerDialog(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    /**
     * 显示插件管理对话框。
     *
     * @param owner 父窗口 (可为 null)
     */
    public void showDialog(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("插件管理");
        dialog.initOwner(owner);
        dialog.setHeaderText("已加载插件");

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("plugin-dialog");
        applyDarkStylesheet(pane);
        pane.getButtonTypes().add(ButtonType.CLOSE);

        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        content.getStyleClass().add("plugin-dialog");

        // 插件表格
        TableView<Plugin> table = createPluginTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        ObservableList<Plugin> data = FXCollections.observableArrayList();
        table.setItems(data);

        // 状态栏 (进度指示 + 提示文本)
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(16, 16);
        progress.setVisible(false);
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("sidebar-title");
        HBox statusBar = new HBox(8, progress, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(2, 0, 2, 0));

        // 工具栏按钮
        Button installBtn = new Button("安装插件");
        Button unloadBtn = new Button("卸载选中");
        Button reloadBtn = new Button("重载选中");
        Button reloadAllBtn = new Button("重载全部");
        Button refreshBtn = new Button("刷新");
        Button[] actionButtons = {installBtn, unloadBtn, reloadBtn, reloadAllBtn, refreshBtn};
        for (Button b : actionButtons) {
            b.getStyleClass().add("toolbar-button");
        }

        installBtn.setOnAction(e -> onInstall(owner, data, statusLabel, progress, actionButtons));
        unloadBtn.setOnAction(e -> onUnload(owner, table, data, statusLabel, progress, actionButtons));
        reloadBtn.setOnAction(e -> onReload(owner, table, data, statusLabel, progress, actionButtons));
        reloadAllBtn.setOnAction(e -> onReloadAll(owner, data, statusLabel, progress, actionButtons));
        refreshBtn.setOnAction(e -> refreshTable(data, statusLabel));

        ToolBar toolbar = new ToolBar(installBtn, unloadBtn, reloadBtn, reloadAllBtn, refreshBtn);
        toolbar.getStyleClass().add("env-toolbar");

        content.getChildren().addAll(toolbar, table, statusBar);
        pane.setContent(content);

        refreshTable(data, statusLabel);
        dialog.showAndWait();
    }

    // ==================== UI 构建 ====================

    /**
     * 创建插件表格 (名称 / 版本 / 状态 / 描述)。
     * 使用 lambda cellValueFactory 而非 PropertyValueFactory,确保对普通 JavaBean 可靠工作。
     */
    private TableView<Plugin> createPluginTable() {
        TableView<Plugin> table = new TableView<>();
        table.getStyleClass().add("env-table");
        table.setPlaceholder(new Label("暂无已加载插件"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Plugin, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getName() != null ? cell.getValue().getName() : cell.getValue().getId()));
        nameCol.setPrefWidth(180);

        TableColumn<Plugin, String> versionCol = new TableColumn<>("版本");
        versionCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getVersion() != null ? cell.getValue().getVersion() : ""));
        versionCol.setPrefWidth(90);

        // Status 列: 按加载状态着色显示
        TableColumn<Plugin, Plugin.LoadStatus> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleObjectProperty<>(
                cell.getValue().getStatus()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Plugin.LoadStatus item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.toString());
                    switch (item) {
                        case LOADED:
                            setStyle("-fx-text-fill: #89d185;");
                            break;
                        case FAILED:
                            setStyle("-fx-text-fill: #f48771;");
                            break;
                        case DISABLED:
                            setStyle("-fx-text-fill: #dcdcaa;");
                            break;
                        default:
                            setStyle("");
                            break;
                    }
                }
            }
        });
        statusCol.setPrefWidth(100);

        TableColumn<Plugin, String> descCol = new TableColumn<>("描述");
        descCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getDescription() != null ? cell.getValue().getDescription() : ""));
        descCol.setPrefWidth(320);

        table.getColumns().addAll(nameCol, versionCol, statusCol, descCol);
        return table;
    }

    // ==================== 后台操作 ====================

    /**
     * 安装插件: 使用 AWT FileDialog 选择 .java 文件, 在后台 Task 中调用
     * {@link PluginService#installPlugin(Path)}。
     */
    private void onInstall(Window owner, ObservableList<Plugin> data, Label statusLabel,
                           ProgressIndicator progress, Button[] buttons) {
        // AWT FileDialog (兼容 Windows Server, 避免 JFileChooser 卡死)
        java.awt.FileDialog fd = new java.awt.FileDialog(
                (java.awt.Frame) null, "选择插件文件 (.java 或 .jar)", java.awt.FileDialog.LOAD);
        fd.setFilenameFilter((dir, name) -> name != null && (name.endsWith(".java") || name.endsWith(".jar")));
        fd.setVisible(true);

        String fileName = fd.getFile();
        String dir = fd.getDirectory();
        if (fileName == null || dir == null) {
            return; // 用户取消
        }
        Path pluginFile = Paths.get(dir, fileName);

        setBusy(true, progress, statusLabel, "正在安装插件: " + fileName + " ...", buttons);

        Task<Plugin> task = new Task<>() {
            @Override
            protected Plugin call() throws Exception {
                return pluginService.installPlugin(pluginFile);
            }
        };
        task.setOnSucceeded(e -> {
            Plugin result = task.getValue();
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            if (result != null && result.getStatus() == Plugin.LoadStatus.FAILED) {
                log.warn("插件安装失败: {}", result.getErrorMessage());
                showError(owner, "插件安装失败: "
                        + (result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误"));
            } else {
                showInfo(owner, "插件安装成功: "
                        + (result != null && result.getName() != null ? result.getName() : fileName));
            }
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件安装异常", ex);
            showError(owner, "插件安装失败: "
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-install").start();
    }

    /**
     * 卸载选中插件: 在后台 Task 中调用 {@link PluginService#unloadPlugin(String)}。
     */
    private void onUnload(Window owner, TableView<Plugin> table, ObservableList<Plugin> data,
                          Label statusLabel, ProgressIndicator progress, Button[] buttons) {
        Plugin selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning(owner, "请先选择要卸载的插件");
            return;
        }
        String pluginId = selected.getId();
        if (pluginId == null || pluginId.isBlank()) {
            showWarning(owner, "该插件没有有效 ID, 无法卸载");
            return;
        }
        if (!confirm(owner, "确定要卸载插件 \"" + safeName(selected) + "\" 吗?\n"
                + "(源文件保留, 下次启动会重新加载)")) {
            return;
        }

        setBusy(true, progress, statusLabel, "正在卸载插件: " + safeName(selected) + " ...", buttons);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                pluginService.unloadPlugin(pluginId);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            showInfo(owner, "插件已卸载: " + safeName(selected));
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件卸载异常", ex);
            showError(owner, "卸载失败: "
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-unload").start();
    }

    /**
     * 重载选中插件: 在后台 Task 中调用 {@link PluginService#reloadPlugin(String)}。
     */
    private void onReload(Window owner, TableView<Plugin> table, ObservableList<Plugin> data,
                          Label statusLabel, ProgressIndicator progress, Button[] buttons) {
        Plugin selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning(owner, "请先选择要重载的插件");
            return;
        }
        String pluginId = selected.getId();
        if (pluginId == null || pluginId.isBlank()) {
            showWarning(owner, "该插件没有有效 ID, 无法重载");
            return;
        }

        setBusy(true, progress, statusLabel, "正在重载插件: " + safeName(selected) + " ...", buttons);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                pluginService.reloadPlugin(pluginId);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            showInfo(owner, "插件已重载: " + safeName(selected));
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件重载异常", ex);
            showError(owner, "重载失败: "
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-reload").start();
    }

    /**
     * 重载全部插件: 在后台 Task 中调用 {@link PluginService#reloadAll()}。
     */
    private void onReloadAll(Window owner, ObservableList<Plugin> data, Label statusLabel,
                             ProgressIndicator progress, Button[] buttons) {
        setBusy(true, progress, statusLabel, "正在重载所有插件 ...", buttons);

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return pluginService.reloadAll();
            }
        };
        task.setOnSucceeded(e -> {
            int count = task.getValue();
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            showInfo(owner, "已重载所有插件, 共加载 " + count + " 个");
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("重载全部插件异常", ex);
            showError(owner, "重载全部失败: "
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-reload-all").start();
    }

    // ==================== 数据刷新与状态 ====================

    /** 从服务重新加载插件列表到表格。 */
    private void refreshTable(ObservableList<Plugin> data, Label statusLabel) {
        List<Plugin> plugins = pluginService.listPlugins();
        data.setAll(plugins);
        if (statusLabel != null) {
            statusLabel.setText("共 " + plugins.size() + " 个插件");
        }
    }

    /**
     * 切换忙碌状态: 显示/隐藏进度指示, 设置提示文本, 禁用/启用操作按钮。
     *
     * @param busy    是否忙碌
     * @param message 忙碌时的提示文本; null 表示清空 (由 refreshTable 回填计数)
     */
    private void setBusy(boolean busy, ProgressIndicator progress, Label statusLabel,
                         String message, Button[] buttons) {
        progress.setVisible(busy);
        if (message != null) {
            statusLabel.setText(message);
        }
        for (Button b : buttons) {
            b.setDisable(busy);
        }
    }

    // ==================== 工具方法 ====================

    /** 获取插件展示名称 (name 为空时回退到 id 或源文件名)。 */
    private String safeName(Plugin plugin) {
        if (plugin == null) {
            return "";
        }
        if (plugin.getName() != null && !plugin.getName().isBlank()) {
            return plugin.getName();
        }
        if (plugin.getId() != null && !plugin.getId().isBlank()) {
            return plugin.getId();
        }
        return "未知插件";
    }

    /** 给对话框面板应用暗色主题样式表 (复用主程序的 dark.css)。 */
    private void applyDarkStylesheet(DialogPane pane) {
        try {
            java.net.URL url = getClass().getResource("/css/dark.css");
            if (url != null) {
                pane.getStylesheets().add(url.toExternalForm());
            }
        } catch (Exception e) {
            log.warn("加载暗色主题样式表失败", e);
        }
    }

    private void showInfo(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showWarning(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("警告");
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private boolean confirm(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认");
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
