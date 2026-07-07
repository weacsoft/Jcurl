package com.jcurl2.ui;

import com.jcurl2.plugin.Plugin;
import com.jcurl2.plugin.PluginService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * 插件管理对话框 — 以表格展示已加载插件, 支持安装 / 卸载 / 启用 / 禁用 / 重载。
 * <p>
 * 数据来源: {@link PluginService#listPlugins()} 返回当前已加载的插件元数据列表。
 * <p>
 * 与 Swing 版 {@code PluginManagerDialog} 保持功能完全一致:
 * <ul>
 *   <li>表格列: 名称 / 版本 / 状态 / 描述 / ID / 扩展点 / 文件路径 (7 列)</li>
 *   <li>按钮: 安装插件 / 卸载插件 / 启用插件 / 禁用插件 / 重载插件 / 重载全部 / 编辑默认插件 / 刷新</li>
 *   <li>右键菜单: 启用 / 禁用 / 重载 / 卸载</li>
 *   <li>状态栏: 显示插件计数 + 编译器可用性提示</li>
 *   <li>卸载后插件从列表消失 (源文件保留, 可重新安装)</li>
 *   <li>重载全部需确认</li>
 *   <li>编辑默认插件前检查编译器可用性</li>
 * </ul>
 * <p>
 * 关键约束 (兼容 Windows Server 环境):
 * <ul>
 *   <li>选择插件文件使用 {@link java.awt.FileDialog} (而非 JFileChooser / JavaFX FileChooser),
 *       避免 Swing 文件选择器在 Windows Server 无桌面会话时卡死。</li>
 *   <li>安装 / 卸载 / 重载操作均在后台线程执行 (使用 {@link javafx.concurrent.Task}),
 *       防止编译 / IO 阻塞 JavaFX UI 线程。操作期间显示进度提示并禁用按钮。</li>
 * </ul>
 */
@Lazy
@Component
public class PluginManagerDialog {

    private static final Logger log = LoggerFactory.getLogger(PluginManagerDialog.class);

    /** 默认插件文件名 (位于插件目录下)。 */
    private static final String DEFAULT_PLUGIN_FILE_NAME = "DefaultPlugin.java";

    /** 默认插件 ID (类名, 用于判断是否已加载)。 */
    private static final String DEFAULT_PLUGIN_ID = "DefaultPlugin";

    /**
     * 默认插件模板代码 — 与 Swing 版完全一致, 展示全部四个扩展点的用法。
     * 保存为 {@code DefaultPlugin.java} 后编译加载。
     */
    private static final String DEFAULT_PLUGIN_TEMPLATE =
            "import com.jcurl2.model.component.Header;\n" +
            "import com.jcurl2.model.dto.RequestConfig;\n" +
            "import com.jcurl2.model.dto.ResponseData;\n" +
            "import com.jcurl2.plugin.JcurlPlugin;\n" +
            "import com.jcurl2.plugin.PluginContext;\n" +
            "import com.jcurl2.plugin.extension.MetricsCollectorExtension;\n" +
            "import com.jcurl2.plugin.extension.RequestInterceptor;\n" +
            "import com.jcurl2.plugin.extension.ResponseInterceptor;\n" +
            "import com.jcurl2.plugin.extension.VariableFunctionExtension;\n" +
            "\n" +
            "import java.util.Arrays;\n" +
            "import java.util.HashMap;\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "\n" +
            "/**\n" +
            " * 默认插件模板 — 可在此处编写插件逻辑。\n" +
            " * 保存后会自动编译并加载（需要 JDK 环境）。\n" +
            " * 使用 @JcurlPlugin 注解提供元数据, 实现了全部四个扩展点, 可按需删除不需要的接口。\n" +
            " */\n" +
            "@JcurlPlugin(name = \"默认插件\", description = \"内置默认插件模板,可编辑后重载\", version = \"1.0.0\", author = \"Jcurl\")\n" +
            "public class DefaultPlugin implements RequestInterceptor, ResponseInterceptor,\n" +
            "        VariableFunctionExtension, MetricsCollectorExtension {\n" +
            "\n" +
            "    // ===== 请求拦截器 =====\n" +
            "    @Override\n" +
            "    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {\n" +
            "        // 在此处修改请求,例如添加请求头:\n" +
            "        // config.getHeaders().add(new Header(\"X-Custom\", \"value\"));\n" +
            "        ctx.log(\"info\", \"[默认插件] 发送请求: \" + config.getMethod() + \" \" + config.getUrl());\n" +
            "        return config;\n" +
            "    }\n" +
            "\n" +
            "    // ===== 响应拦截器 =====\n" +
            "    @Override\n" +
            "    public ResponseData afterResponse(ResponseData response, RequestConfig config, PluginContext ctx) {\n" +
            "        // 在此处处理响应,例如记录日志:\n" +
            "        // ctx.log(\"info\", \"响应状态: \" + response.getStatusCode());\n" +
            "        return response;\n" +
            "    }\n" +
            "\n" +
            "    // ===== 变量函数 =====\n" +
            "    @Override\n" +
            "    public List<String> getFunctionNames(PluginContext ctx) {\n" +
            "        return Arrays.asList(\"hello\");\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public String executeFunction(String functionName, String args, PluginContext ctx) {\n" +
            "        if (\"hello\".equals(functionName)) {\n" +
            "            return \"hello, \" + (args != null ? args : \"world\");\n" +
            "        }\n" +
            "        return null;\n" +
            "    }\n" +
            "\n" +
            "    // ===== 指标采集 =====\n" +
            "    @Override\n" +
            "    public List<String> getMetricNames() {\n" +
            "        return Arrays.asList(\"status_code\");\n" +
            "    }\n" +
            "\n" +
            "    @Override\n" +
            "    public Map<String, Double> collectMetrics(RequestConfig config, ResponseData response, PluginContext ctx) {\n" +
            "        Map<String, Double> metrics = new HashMap<String, Double>();\n" +
            "        metrics.put(\"status_code\", (double) response.getStatusCode());\n" +
            "        return metrics;\n" +
            "    }\n" +
            "}\n";

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
        Label statusLabel = new Label("就绪");
        statusLabel.getStyleClass().add("sidebar-title");
        HBox statusBar = new HBox(8, progress, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(2, 0, 2, 0));

        // 工具栏按钮 — 标签与 Swing 版完全一致
        Button installBtn = new Button("安装插件");
        installBtn.setTooltip(new Tooltip("选择 .java 或 .jar 文件,复制到插件目录并加载"));
        Button unloadBtn = new Button("卸载插件");
        unloadBtn.setTooltip(new Tooltip("卸载选中插件 (从注册表移除,保留源文件)"));
        Button enableBtn = new Button("启用插件");
        enableBtn.setTooltip(new Tooltip("启用选中插件 (重新注册扩展点)"));
        Button disableBtn = new Button("禁用插件");
        disableBtn.setTooltip(new Tooltip("禁用选中插件 (取消注册扩展点,保留实例)"));
        Button reloadBtn = new Button("重载插件");
        reloadBtn.setTooltip(new Tooltip("重载选中插件 (先卸载再加载)"));
        Button reloadAllBtn = new Button("重载全部");
        reloadAllBtn.setTooltip(new Tooltip("重载所有插件"));
        Button editDefaultBtn = new Button("编辑默认插件");
        editDefaultBtn.setTooltip(new Tooltip("编辑内置默认插件模板,保存后编译并重载"));
        Button refreshBtn = new Button("刷新");
        refreshBtn.setTooltip(new Tooltip("刷新插件列表"));
        Button[] actionButtons = {installBtn, unloadBtn, enableBtn, disableBtn, reloadBtn,
                reloadAllBtn, editDefaultBtn, refreshBtn};
        for (Button b : actionButtons) {
            b.getStyleClass().add("toolbar-button");
        }

        installBtn.setOnAction(e -> onInstall(owner, data, statusLabel, progress, actionButtons));
        unloadBtn.setOnAction(e -> onUnload(owner, table, data, statusLabel, progress, actionButtons));
        enableBtn.setOnAction(e -> onEnable(owner, table, data, statusLabel, progress, actionButtons));
        disableBtn.setOnAction(e -> onDisable(owner, table, data, statusLabel, progress, actionButtons));
        reloadBtn.setOnAction(e -> onReload(owner, table, data, statusLabel, progress, actionButtons));
        reloadAllBtn.setOnAction(e -> onReloadAll(owner, data, statusLabel, progress, actionButtons));
        editDefaultBtn.setOnAction(e -> onEditDefault(owner, data, statusLabel, progress, actionButtons));
        refreshBtn.setOnAction(e -> refreshTable(data, statusLabel));

        ToolBar toolbar = new ToolBar(installBtn, unloadBtn, enableBtn, disableBtn, reloadBtn,
                reloadAllBtn, editDefaultBtn, refreshBtn);
        toolbar.getStyleClass().add("env-toolbar");

        // 右键上下文菜单 — 与 Swing 版一致
        ContextMenu contextMenu = new ContextMenu();
        MenuItem enableItem = new MenuItem("启用插件");
        enableItem.setOnAction(e -> onEnable(owner, table, data, statusLabel, progress, actionButtons));
        MenuItem disableItem = new MenuItem("禁用插件");
        disableItem.setOnAction(e -> onDisable(owner, table, data, statusLabel, progress, actionButtons));
        MenuItem reloadItem = new MenuItem("重载插件");
        reloadItem.setOnAction(e -> onReload(owner, table, data, statusLabel, progress, actionButtons));
        MenuItem unloadItem = new MenuItem("卸载插件");
        unloadItem.setOnAction(e -> onUnload(owner, table, data, statusLabel, progress, actionButtons));
        contextMenu.getItems().addAll(enableItem, disableItem, reloadItem,
                new SeparatorMenuItem(), unloadItem);
        table.setContextMenu(contextMenu);

        // 右键时自动选中光标所在行
        table.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                int row = table.getSelectionModel().getSelectedIndex();
                // JavaFX TableView 默认右键即可选中行,无需额外处理
            }
        });

        content.getChildren().addAll(toolbar, table, statusBar);
        pane.setContent(content);

        refreshTable(data, statusLabel);
        dialog.showAndWait();
    }

    // ==================== UI 构建 ====================

    /**
     * 创建插件表格 — 7 列,与 Swing 版完全一致。
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
        nameCol.setPrefWidth(120);

        TableColumn<Plugin, String> versionCol = new TableColumn<>("版本");
        versionCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getVersion() != null ? cell.getValue().getVersion() : ""));
        versionCol.setPrefWidth(60);

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
                            setStyle("-fx-text-fill: #4caf50;");
                            break;
                        case FAILED:
                            setStyle("-fx-text-fill: #f44336;");
                            break;
                        case DISABLED:
                            setStyle("-fx-text-fill: #ffc107;");
                            break;
                        default:
                            setStyle("-fx-text-fill: gray;");
                            break;
                    }
                }
            }
        });
        statusCol.setPrefWidth(70);

        TableColumn<Plugin, String> descCol = new TableColumn<>("描述");
        descCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getDescription() != null ? cell.getValue().getDescription() : ""));
        descCol.setPrefWidth(180);

        TableColumn<Plugin, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getId() != null ? cell.getValue().getId() : ""));
        idCol.setPrefWidth(140);

        TableColumn<Plugin, String> extCol = new TableColumn<>("扩展点");
        extCol.setCellValueFactory(cell -> {
            List<String> eps = cell.getValue().getExtensionPoints();
            String text = (eps != null && !eps.isEmpty()) ? String.join(", ", eps) : "";
            return new javafx.beans.property.SimpleStringProperty(text);
        });
        extCol.setPrefWidth(160);

        TableColumn<Plugin, String> pathCol = new TableColumn<>("文件路径");
        pathCol.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().getSourceFile() != null ? cell.getValue().getSourceFile() : ""));
        pathCol.setPrefWidth(280);

        table.getColumns().addAll(nameCol, versionCol, statusCol, descCol, idCol, extCol, pathCol);
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
     * 卸载后插件从列表消失 (与 Swing 版一致)。
     */
    private void onUnload(Window owner, TableView<Plugin> table, ObservableList<Plugin> data,
                          Label statusLabel, ProgressIndicator progress, Button[] buttons) {
        Plugin selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning(owner, "请先在表格中选择一个插件");
            return;
        }
        String pluginId = selected.getId();
        if (pluginId == null || pluginId.isBlank()) {
            showWarning(owner, "该插件没有有效 ID, 无法卸载");
            return;
        }
        if (!confirm(owner, "确定要卸载插件 \"" + pluginId + "\" 吗?\n(源文件将保留, 可重新加载)")) {
            return;
        }

        setBusy(true, progress, statusLabel, "正在卸载插件: " + pluginId + " ...", buttons);

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
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件卸载异常", ex);
            showError(owner, "卸载插件失败:\n"
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
            showWarning(owner, "请先在表格中选择一个插件");
            return;
        }
        String pluginId = selected.getId();
        if (pluginId == null || pluginId.isBlank()) {
            showWarning(owner, "该插件没有有效 ID, 无法重载");
            return;
        }

        setBusy(true, progress, statusLabel, "正在重载插件: " + pluginId + " ...", buttons);

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
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件重载异常", ex);
            showError(owner, "重载插件失败:\n"
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-reload").start();
    }

    /**
     * 重载全部插件: 需用户确认, 在后台 Task 中调用 {@link PluginService#reloadAll()}。
     */
    private void onReloadAll(Window owner, ObservableList<Plugin> data, Label statusLabel,
                             ProgressIndicator progress, Button[] buttons) {
        if (!confirm(owner, "确定要重载所有插件吗?")) {
            return;
        }

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
            showError(owner, "重载全部失败:\n"
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-reload-all").start();
    }

    /**
     * 启用选中插件: 在后台 Task 中调用 {@link PluginService#enablePlugin(String)}。
     */
    private void onEnable(Window owner, TableView<Plugin> table, ObservableList<Plugin> data,
                          Label statusLabel, ProgressIndicator progress, Button[] buttons) {
        Plugin selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning(owner, "请先在表格中选择一个插件");
            return;
        }
        String pluginId = selected.getId();
        if (pluginId == null || pluginId.isBlank()) {
            showWarning(owner, "该插件没有有效 ID, 无法启用");
            return;
        }

        setBusy(true, progress, statusLabel, "正在启用插件: " + pluginId + " ...", buttons);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                pluginService.enablePlugin(pluginId);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件启用异常", ex);
            showError(owner, "启用插件失败:\n"
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-enable").start();
    }

    /**
     * 禁用选中插件: 在后台 Task 中调用 {@link PluginService#disablePlugin(String)}。
     */
    private void onDisable(Window owner, TableView<Plugin> table, ObservableList<Plugin> data,
                           Label statusLabel, ProgressIndicator progress, Button[] buttons) {
        Plugin selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning(owner, "请先在表格中选择一个插件");
            return;
        }
        String pluginId = selected.getId();
        if (pluginId == null || pluginId.isBlank()) {
            showWarning(owner, "该插件没有有效 ID, 无法禁用");
            return;
        }

        setBusy(true, progress, statusLabel, "正在禁用插件: " + pluginId + " ...", buttons);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                pluginService.disablePlugin(pluginId);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("插件禁用异常", ex);
            showError(owner, "禁用插件失败:\n"
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-disable").start();
    }

    /**
     * 编辑默认插件: 弹出代码编辑器对话框, 允许编辑 {@code DefaultPlugin.java} 源码并保存重载。
     * <p>
     * 流程:
     * <ol>
     *   <li>检查编译器可用性 (不可用时弹警告, 用户可选择继续)</li>
     *   <li>读取 {@code DefaultPlugin.java} 内容 (不存在则使用内置模板)</li>
     *   <li>在 TextArea 中展示供用户编辑</li>
     *   <li>"保存并重载": 写回文件, 若已加载则 reload, 否则 install (后台 Task 执行编译加载)</li>
     * </ol>
     */
    private void onEditDefault(Window owner, ObservableList<Plugin> data, Label statusLabel,
                               ProgressIndicator progress, Button[] buttons) {
        // 0. 编译器可用性检查 (与 Swing 版一致)
        boolean compilerAvailable = isCompilerAvailable();
        if (!compilerAvailable) {
            if (!confirm(owner, "Java 编译器不可用 (可能运行在 JRE 环境)。\n.java 插件将无法编译。\n是否继续编辑?")) {
                return;
            }
        }

        // 1. 确定默认插件文件路径
        Path defaultPluginFile = pluginService.getPluginsDir().resolve(DEFAULT_PLUGIN_FILE_NAME);

        // 2. 读取已有内容 (不存在则使用内置模板)
        String initialContent;
        try {
            if (Files.exists(defaultPluginFile)) {
                initialContent = Files.readString(defaultPluginFile);
            } else {
                initialContent = DEFAULT_PLUGIN_TEMPLATE;
            }
        } catch (Exception e) {
            log.error("读取默认插件文件失败: {}", defaultPluginFile, e);
            showError(owner, "读取默认插件文件失败: "
                    + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            return;
        }

        // 3. 构建编辑对话框
        Dialog<String> editDialog = new Dialog<>();
        editDialog.setTitle("编辑默认插件");
        editDialog.initOwner(owner);
        editDialog.setHeaderText("编辑默认插件代码, 保存后将编译并重载 (需要 JDK 环境):");

        DialogPane editPane = editDialog.getDialogPane();
        editPane.getStyleClass().add("plugin-dialog");
        applyDarkStylesheet(editPane);

        // 自定义按钮: 保存并重载 / 取消
        ButtonType saveReloadType = new ButtonType("保存并重载", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        editPane.getButtonTypes().addAll(saveReloadType, cancelType);

        TextArea codeArea = new TextArea(initialContent);
        codeArea.setWrapText(false);
        codeArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        codeArea.setPrefSize(720, 560);
        VBox.setVgrow(codeArea, Priority.ALWAYS);

        VBox editContent = new VBox(8, codeArea);
        editContent.setPadding(new Insets(12));
        editContent.getStyleClass().add("plugin-dialog");
        editPane.setContent(editContent);

        // 返回编辑后的内容 (保存并重载时); 取消时返回 null
        editDialog.setResultConverter(btn -> btn == saveReloadType ? codeArea.getText() : null);

        Optional<String> result = editDialog.showAndWait();
        if (result.isEmpty()) {
            return; // 用户取消
        }
        String code = result.get();

        // 空代码检查 (与 Swing 版一致)
        if (code == null || code.trim().isEmpty()) {
            showWarning(owner, "代码不能为空");
            return;
        }

        // 4. 后台保存 + 编译加载
        setBusy(true, progress, statusLabel, "正在保存并重载默认插件 ...", buttons);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                Files.createDirectories(pluginService.getPluginsDir());
                Files.writeString(defaultPluginFile, code);
                // 判断 DefaultPlugin 是否已加载
                boolean loaded = pluginService.listPlugins().stream()
                        .anyMatch(p -> DEFAULT_PLUGIN_ID.equals(p.getId()));
                if (loaded) {
                    pluginService.reloadPlugin(DEFAULT_PLUGIN_ID);
                    return "reload";
                } else {
                    pluginService.installPlugin(defaultPluginFile);
                    return "install";
                }
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            String mode = task.getValue();
            showInfo(owner, "默认插件已保存并" + ("reload".equals(mode) ? "重载" : "加载"));
        });
        task.setOnFailed(e -> {
            setBusy(false, progress, statusLabel, null, buttons);
            refreshTable(data, statusLabel);
            Throwable ex = task.getException();
            log.error("保存/重载默认插件失败", ex);
            showError(owner, "保存并重载失败:\n"
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "未知错误"));
        });
        new Thread(task, "plugin-edit-default").start();
    }

    // ==================== 数据刷新与状态 ====================

    /**
     * 从服务重新加载插件列表到表格。
     * 状态栏显示插件计数 + 编译器可用性提示 (与 Swing 版一致)。
     */
    private void refreshTable(ObservableList<Plugin> data, Label statusLabel) {
        List<Plugin> plugins = pluginService.listPlugins();
        data.setAll(plugins);
        if (statusLabel != null) {
            String compilerHint = isCompilerAvailable()
                    ? "" : " (编译器不可用, .java 插件将被跳过)";
            statusLabel.setText("已加载 " + plugins.size() + " 个插件" + compilerHint);
        }
    }

    /**
     * 检查 Java 编译器是否可用。
     * 通过 {@link javax.tools.ToolProvider#getSystemJavaCompiler()} 判断。
     */
    private boolean isCompilerAvailable() {
        return javax.tools.ToolProvider.getSystemJavaCompiler() != null;
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
