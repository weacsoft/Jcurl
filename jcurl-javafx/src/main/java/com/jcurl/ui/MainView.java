package com.jcurl.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.Collection;
import com.jcurl.model.HistoryRecord;
import com.jcurl.plugin.model.dto.RequestConfig;
import com.jcurl.plugin.PluginService;
import com.jcurl.service.CollectionService;
import com.jcurl.service.CookieService;
import com.jcurl.service.EnvironmentService;
import com.jcurl.service.ImportExportService;
import com.jcurl.store.JsonStoreService;
import com.jcurl.ui.event.RequestSelectionListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 主界面视图 — 构建 JavaFX 主窗口根布局 (Win11 优化版)。
 * <p>
 * 布局结构(BorderPane):
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │  MenuBar (文件/编辑/视图/帮助)            │
 * │  ToolBar (标题/导入/导出/主题/插件/Cookie) │
 * ├──────────┬──────────────────────────────┤
 * │  左侧栏   │  API 调试 / 性能测试           │
 * │ 集合/历史  │                              │
 * │ /环境     │                              │
 * ├──────────┴──────────────────────────────┤
 * │ 状态栏: 状态信息 | Cookie计数 | 数据路径   │
 * └─────────────────────────────────────────┘
 * </pre>
 * <p>
 * 增强(Win11 版):
 * <ul>
 *   <li>新增 MenuBar, 提供文件/编辑/视图/帮助菜单</li>
 *   <li>集成 CookieService, 选中集合请求时自动切换 Cookie 上下文</li>
 *   <li>状态栏显示当前集合 Cookie 计数</li>
 *   <li>使用独立 CookieManagerDialog / PluginManagerDialog 替代内联弹窗</li>
 * </ul>
 */
@Lazy
@Component
public class MainView {

    private static final Logger log = LoggerFactory.getLogger(MainView.class);

    private final JsonStoreService storeService;
    private final CollectionTreeView collectionTreeView;
    private final RequestBuilderView requestBuilderView;
    private final ResponseView responseView;
    private final EnvironmentView environmentView;
    private final HistoryView historyView;
    private final PerformanceTestView performanceTestView;
    private final ObjectMapper objectMapper;
    private final ImportExportService importExportService;
    private final CollectionService collectionService;
    private final PluginService pluginService;
    private final CookieService cookieService;
    private final CookieManagerDialog cookieManagerDialog;
    private final PluginManagerDialog pluginManagerDialog;
    private final com.jcurl.service.HistoryService historyService;
    private final com.jcurl.service.EnvironmentService environmentService;

    private BorderPane root;
    private Label statusLabel;
    private Label cookieCountLabel;
    private Label envLabel;
    private Scene scene;
    private boolean darkTheme = true;

    public MainView(JsonStoreService storeService,
                    CollectionTreeView collectionTreeView,
                    RequestBuilderView requestBuilderView,
                    ResponseView responseView,
                    EnvironmentView environmentView,
                    HistoryView historyView,
                    PerformanceTestView performanceTestView,
                    ObjectMapper objectMapper,
                    ImportExportService importExportService,
                    CollectionService collectionService,
                    PluginService pluginService,
                    CookieService cookieService,
                    CookieManagerDialog cookieManagerDialog,
                    PluginManagerDialog pluginManagerDialog,
                    com.jcurl.service.HistoryService historyService,
                    com.jcurl.service.EnvironmentService environmentService) {
        this.storeService = storeService;
        this.collectionTreeView = collectionTreeView;
        this.requestBuilderView = requestBuilderView;
        this.responseView = responseView;
        this.environmentView = environmentView;
        this.historyView = historyView;
        this.performanceTestView = performanceTestView;
        this.objectMapper = objectMapper;
        this.importExportService = importExportService;
        this.collectionService = collectionService;
        this.pluginService = pluginService;
        this.cookieService = cookieService;
        this.cookieManagerDialog = cookieManagerDialog;
        this.pluginManagerDialog = pluginManagerDialog;
        this.historyService = historyService;
        this.environmentService = environmentService;
        buildView();
    }

    public BorderPane getRoot() {
        return root;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
        setupKeyboardShortcuts();
    }

    /** 初始化数据与事件链路 */
    public void initialize() {
        // 包装请求选中监听器: 先切换 Cookie 上下文, 再通知请求构建器
        collectionTreeView.setSelectionListener((collection, request) -> {
            cookieService.setCurrentCollection(collection != null ? collection.getId() : null);
            updateCookieCount();
            requestBuilderView.onRequestSelected(collection, request);
        });

        requestBuilderView.setStatusUpdater(this::updateStatus);
        requestBuilderView.setResponseListener(responseView);
        historyView.setSelectionCallback(this::loadHistoryRecord);
        performanceTestView.setRequestConfigSupplier(this::getCurrentRequestConfigJson);
        collectionTreeView.refresh();
        historyView.refresh();
        updateEnvDisplay();
        updateCookieCount();
        updateStatus("就绪");
    }

    private void loadHistoryRecord(HistoryRecord record) {
        // 历史记录不关联集合, 切换到全局 Cookie 上下文
        cookieService.setCurrentCollection(null);
        updateCookieCount();
        requestBuilderView.loadFromHistory(record);
        updateStatus("已从历史加载: " + record.getMethod() + " " + record.getUrl());
    }

    private String getCurrentRequestConfigJson() {
        try {
            RequestConfig config = requestBuilderView.getCurrentConfig();
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            return null;
        }
    }

    public void refreshCollectionTree() {
        collectionTreeView.refresh();
    }

    public void refreshHistory() {
        historyView.refresh();
    }

    public void updateStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    /** 更新状态栏 Cookie 计数 */
    private void updateCookieCount() {
        if (cookieCountLabel != null) {
            int count = cookieService.getCookieCount();
            cookieCountLabel.setText("Cookie: " + count);
        }
    }

    /** 更新状态栏环境显示 */
    private void updateEnvDisplay() {
        if (envLabel != null) {
            var env = environmentService.getActiveEnvironment();
            String envName = env != null ? env.getName() : null;
            envLabel.setText("环境: " + (envName != null ? envName : "无"));
        }
    }

    // ==================== UI 构建 ====================

    private void buildView() {
        root = new BorderPane();
        root.getStyleClass().add("main-root");

        // 顶部: MenuBar + ToolBar
        VBox topBox = new VBox(0);
        topBox.getChildren().addAll(buildMenuBar(), buildToolBar());
        root.setTop(topBox);

        root.setLeft(buildSidebar());
        root.setCenter(buildContentArea());
        root.setBottom(buildStatusBar());
    }

    /** 顶部菜单栏 */
    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.getStyleClass().add("main-menubar");

        // 文件菜单
        Menu fileMenu = new Menu("文件");
        MenuItem newRequestItem = new MenuItem("新建请求");
        newRequestItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        newRequestItem.setOnAction(e -> {
            requestBuilderView.focusUrlField();
            updateStatus("新建请求");
        });

        MenuItem importItem = new MenuItem("导入...");
        importItem.setOnAction(e -> showImportDialog());

        MenuItem exportItem = new MenuItem("导出...");
        exportItem.setOnAction(e -> showExportDialog());

        SeparatorMenuItem sep1 = new SeparatorMenuItem();
        MenuItem exitItem = new MenuItem("退出");
        exitItem.setOnAction(e -> {
            Stage stage = (Stage) root.getScene().getWindow();
            stage.close();
            javafx.application.Platform.exit();
        });

        fileMenu.getItems().addAll(newRequestItem, new SeparatorMenuItem(),
                importItem, exportItem, sep1, exitItem);

        // 编辑菜单
        Menu editMenu = new Menu("编辑");
        MenuItem clearHistoryItem = new MenuItem("清空历史记录");
        clearHistoryItem.setOnAction(e -> {
            try {
                historyService.clearHistory();
                historyView.refresh();
                updateStatus("已清空历史记录");
            } catch (Exception ex) {
                showError("清空历史失败: " + ex.getMessage());
            }
        });

        MenuItem clearCookiesItem = new MenuItem("清空当前集合 Cookie");
        clearCookiesItem.setOnAction(e -> {
            cookieService.clearAll();
            updateCookieCount();
            updateStatus("已清空当前集合的 Cookie");
        });

        editMenu.getItems().addAll(clearHistoryItem, clearCookiesItem);

        // 视图菜单
        Menu viewMenu = new Menu("视图");
        RadioMenuItem darkItem = new RadioMenuItem("暗色主题");
        RadioMenuItem lightItem = new RadioMenuItem("亮色主题");
        ToggleGroup themeGroup = new ToggleGroup();
        darkItem.setToggleGroup(themeGroup);
        lightItem.setToggleGroup(themeGroup);
        darkItem.setSelected(true);
        darkItem.setOnAction(e -> {
            if (!darkTheme) toggleTheme();
        });
        lightItem.setOnAction(e -> {
            if (darkTheme) toggleTheme();
        });

        viewMenu.getItems().addAll(darkItem, lightItem);

        // 帮助菜单
        Menu helpMenu = new Menu("帮助");
        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> showAboutDialog());
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, editMenu, viewMenu, helpMenu);
        return menuBar;
    }

    /** 顶部工具栏 */
    private ToolBar buildToolBar() {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("main-toolbar");

        Label title = new Label("Jcurl");
        title.getStyleClass().add("toolbar-title");

        Button importBtn = new Button("导入");
        importBtn.getStyleClass().add("toolbar-button");
        importBtn.setOnAction(e -> showImportDialog());

        Button exportBtn = new Button("导出");
        exportBtn.getStyleClass().add("toolbar-button");
        exportBtn.setOnAction(e -> showExportDialog());

        Button themeBtn = new Button("主题");
        themeBtn.getStyleClass().add("toolbar-button");
        themeBtn.setOnAction(e -> toggleTheme());

        Button pluginBtn = new Button("插件");
        pluginBtn.getStyleClass().add("toolbar-button");
        pluginBtn.setOnAction(e -> {
            Stage stage = (Stage) root.getScene().getWindow();
            pluginManagerDialog.showDialog(stage);
        });

        Button cookieBtn = new Button("Cookie");
        cookieBtn.getStyleClass().add("toolbar-button");
        cookieBtn.setOnAction(e -> {
            Stage stage = (Stage) root.getScene().getWindow();
            cookieManagerDialog.showDialog(stage);
            updateCookieCount();
        });

        Label dataPath = new Label("数据: " + shortenPath(storeService.getBaseDir().toString()));
        dataPath.getStyleClass().add("toolbar-path");
        dataPath.setOpacity(0.6);

        toolBar.getItems().addAll(
                title, new Separator(),
                importBtn, exportBtn,
                new Separator(),
                themeBtn, pluginBtn, cookieBtn,
                new Separator(),
                dataPath
        );
        return toolBar;
    }

    /** 缩短路径显示 (只保留最后两级) */
    private String shortenPath(String path) {
        if (path == null) return "";
        String normalized = path.replace("\\", "/");
        int idx = normalized.lastIndexOf("/");
        if (idx < 0) return path;
        int idx2 = normalized.lastIndexOf("/", idx - 1);
        if (idx2 < 0) return "..." + path.substring(idx);
        return "..." + path.substring(idx2);
    }

    /** 左侧栏 */
    private TabPane buildSidebar() {
        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("sidebar");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setPrefWidth(300);
        tabPane.setMinWidth(220);

        Tab collectionTab = new Tab("集合");
        collectionTab.setContent(collectionTreeView.buildView());

        Tab historyTab = new Tab("历史");
        historyTab.setContent(historyView.getRoot());

        Tab envTab = new Tab("环境");
        envTab.setContent(environmentView.getRoot());

        tabPane.getTabs().addAll(collectionTab, historyTab, envTab);
        return tabPane;
    }

    /** 中间内容区 */
    private TabPane buildContentArea() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("content-tabs");

        SplitPane apiSplit = new SplitPane();
        apiSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        apiSplit.setDividerPositions(0.45);
        apiSplit.getItems().addAll(requestBuilderView.getRoot(), responseView.getRoot());

        Tab apiTab = new Tab("API 调试");
        apiTab.setContent(apiSplit);

        Tab perfTab = new Tab("性能测试");
        perfTab.setContent(performanceTestView.getRoot());

        tabPane.getTabs().addAll(apiTab, perfTab);
        return tabPane;
    }

    /** 底部状态栏: 状态信息 | Cookie 计数 | 环境信息 */
    private HBox buildStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().add("status-text");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        cookieCountLabel = new Label("Cookie: 0");
        cookieCountLabel.getStyleClass().add("status-text");
        cookieCountLabel.setStyle("-fx-text-fill: #4ec9b0;");

        envLabel = new Label("环境: 无");
        envLabel.getStyleClass().add("status-text");
        envLabel.setStyle("-fx-text-fill: #888;");

        statusBar.getChildren().addAll(statusLabel, new Separator(),
                cookieCountLabel, new Separator(), envLabel);
        return statusBar;
    }

    // ==================== 导入/导出 ====================

    private void showImportDialog() {
        ChoiceDialog<String> dialog = new ChoiceDialog<>("Postman v2.1", "Postman v2.1", "OpenAPI 3.0", "cURL");
        dialog.setTitle("导入");
        dialog.setHeaderText("选择导入格式");
        dialog.setContentText("格式:");

        dialog.showAndWait().ifPresent(format -> {
            FileChooser fileChooser = new FileChooser();
            if (format.equals("cURL")) {
                fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("文本文件", "*.txt", "*.sh"));
            } else {
                fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
            }
            File file = fileChooser.showOpenDialog(root.getScene().getWindow());
            if (file == null) return;

            try {
                String content = Files.readString(file.toPath());
                switch (format) {
                    case "Postman v2.1" -> {
                        Collection col = importExportService.importPostmanV21(content);
                        collectionService.saveCollection(col);
                        collectionTreeView.refresh();
                        updateStatus("已导入 Postman 集合: " + col.getName());
                    }
                    case "OpenAPI 3.0" -> {
                        List<Collection> cols = importExportService.importOpenApi30(content);
                        for (Collection c : cols) {
                            collectionService.saveCollection(c);
                        }
                        collectionTreeView.refresh();
                        updateStatus("已导入 " + cols.size() + " 个 OpenAPI 集合");
                    }
                    case "cURL" -> {
                        var request = importExportService.importCurl(content);
                        Collection col = collectionService.createCollection("cURL 导入");
                        collectionService.addRequest(col, null, request.getName(), request.getMethod(), request.getUrl());
                        collectionTreeView.refresh();
                        updateStatus("已从 cURL 导入请求");
                    }
                }
            } catch (Exception e) {
                log.error("导入失败", e);
                showError("导入失败: " + e.getMessage());
            }
        });
    }

    private void showExportDialog() {
        List<Collection> collections = collectionService.listCollections();
        if (collections.isEmpty()) {
            showInfo("没有可导出的集合");
            return;
        }

        ChoiceDialog<Collection> dialog = new ChoiceDialog<>(collections.get(0), collections);
        dialog.setTitle("导出");
        dialog.setHeaderText("选择要导出的集合");
        dialog.setContentText("集合:");

        dialog.showAndWait().ifPresent(col -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setInitialFileName(col.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".json");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Postman v2.1 JSON", "*.json"));
            File file = fileChooser.showSaveDialog(root.getScene().getWindow());
            if (file == null) return;

            try {
                String json = importExportService.exportPostmanV21(col);
                Files.writeString(file.toPath(), json);
                updateStatus("已导出: " + file.getName());
            } catch (Exception e) {
                log.error("导出失败", e);
                showError("导出失败: " + e.getMessage());
            }
        });
    }

    // ==================== 主题切换 ====================

    private void toggleTheme() {
        darkTheme = !darkTheme;
        if (scene != null) {
            scene.getStylesheets().clear();
            String css = darkTheme ? "/css/dark.css" : "/css/light.css";
            scene.getStylesheets().add(getClass().getResource(css).toExternalForm());
        }
        updateStatus("主题: " + (darkTheme ? "暗色" : "亮色"));
    }

    // ==================== 关于对话框 ====================

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于");
        alert.setHeaderText("Jcurl");
        alert.setContentText("JavaFX 21 桌面 API 客户端\nJDK 17+ / SpringBoot 3.2 / Win11 优化版\n\n支持: HTTP 请求 / 集合管理 / 环境变量 / 插件系统 / 性能测试");
        alert.showAndWait();
    }

    // ==================== 快捷键 ====================

    private void setupKeyboardShortcuts() {
        if (scene == null) return;

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            // Ctrl+Enter: 发送请求
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(event)) {
                requestBuilderView.triggerSend();
                event.consume();
                return;
            }
            // Ctrl+L: 聚焦 URL 输入框
            if (new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN).match(event)) {
                requestBuilderView.focusUrlField();
                event.consume();
            }
        });
    }

    // ==================== 辅助 ====================

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
