package com.jcurl.ui;

import com.jcurl.model.Collection;
import com.jcurl.model.Environment;
import com.jcurl.model.GlobalVariables;
import com.jcurl.model.RequestNode;
import com.jcurl.plugin.model.component.*;
import com.jcurl.plugin.model.dto.RequestConfig;
import com.jcurl.plugin.model.dto.ResponseData;
import com.jcurl.service.*;
import com.jcurl.ui.event.RequestSelectionListener;
import com.jcurl.ui.event.ResponseListener;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 请求构建器视图 — 中间内容区核心组件,用于编辑和发送 HTTP 请求。
 * <p>
 * 布局:
 * <pre>
 * ┌────────────────────────────────────────┐
 * │ [Method▼] [URL输入框          ] [Send] │ ← 请求栏
 * ├────────────────────────────────────────┤
 * │ [Params] [Headers] [Body] [Auth]       │ ← 标签页
 * │                                        │
 * │           标签页内容区域                 │
 * │                                        │
 * └────────────────────────────────────────┘
 * </pre>
 * <p>
 * 实现 {@link RequestSelectionListener}:集合树选中请求时自动加载配置到 UI。
 * 发送请求后通过 {@link ResponseListener} 通知响应展示区域。
 */
@Lazy
@Component
public class RequestBuilderView implements RequestSelectionListener {

    private static final Logger log = LoggerFactory.getLogger(RequestBuilderView.class);

    /** 常见请求头(用于 Headers Key 列下拉自动补全) */
    private static final String[] COMMON_HEADERS = {
            "Accept", "Authorization", "Content-Type", "Cookie", "X-Requested-With",
            "X-API-Key", "Accept-Encoding", "Accept-Language", "User-Agent",
            "Cache-Control", "Host", "Origin", "Referer", "Connection", "Content-Length"
    };

    private final HttpEngineService httpEngine;
    private final VariableResolver variableResolver;
    private final InheritanceMerger inheritanceMerger;
    private final CollectionService collectionService;
    private final HistoryService historyService;
    private final EnvironmentService environmentService;
    private final DefaultHeaderProvider defaultHeaderProvider;

    /** 响应监听器(由响应展示视图设置) */
    private ResponseListener responseListener;

    /** 状态更新回调(由 MainView 设置) */
    private Consumer<String> statusUpdater;

    /** 当前选中的请求节点 */
    private RequestNode currentRequest;

    /** 当前请求所属集合 */
    private Collection currentCollection;

    // UI 组件
    private VBox root;
    private ComboBox<String> methodCombo;
    private TextField urlField;
    private Button sendButton;
    private Button saveButton;
    private Button cancelButton;
    private TabPane tabPane;

    // Params 标签页
    private TableView<KeyValueRow> paramsTable;
    private final ObservableList<KeyValueRow> paramsData = FXCollections.observableArrayList();

    // Headers 标签页
    private TableView<KeyValueRow> headersTable;
    /** Headers 数据源(带 extractor,enabled 变化时通知 FilteredList) */
    private final ObservableList<KeyValueRow> headersData =
            FXCollections.observableArrayList(row -> new Observable[]{ row.enabledProperty() });
    /** Headers 过滤视图(保留以兼容 createKeyValueTable,不再过滤) */
    private final FilteredList<KeyValueRow> headersFiltered = new FilteredList<>(headersData);
    /** 自动生成的头(只读)展示区域 */
    private VBox autoHeadersBox;
    private boolean showAutoHeaders = true;
    /**
     * 被用户在 autoHeadersBox 中取消勾选的自动头 key(小写)。
     * <p>
     * 默认为空(所有自动头都发送);用户取消勾选某个自动头时,将其 key(小写)加入此集合,
     * collectConfig() 会据此生成对应的 disabled Header,经 mergeEffective 移除该自动头。
     */
    private final Set<String> disabledAutoHeaderKeys = new HashSet<>();

    // Body 标签页
    private ComboBox<String> bodyTypeCombo;
    private VBox bodyContent;
    private TextArea rawBodyArea;
    private ComboBox<String> rawTypeCombo;
    private TableView<FormDataRow> formDataTable;
    private final ObservableList<FormDataRow> formDataData = FXCollections.observableArrayList();
    private Label binaryFileLabel;
    private String binaryFilePath;
    /** binary 文件内容(Base64 编码) */
    private String binaryFileContent;
    /** binary 文件名(不含路径) */
    private String binaryFileName;

    // Auth 标签页
    private ComboBox<String> authTypeCombo;
    private VBox authContent;
    private TextField basicUsername;
    private PasswordField basicPassword;
    private TextField bearerToken;
    private TextField apiKeyField;
    private TextField apiKeyValue;
    private ComboBox<String> apiKeyAddTo;

    /** URL ↔ Params 同步防重入标志 */
    private boolean syncingUrlParams = false;

    /** 当前请求的 executionId(用于取消) */
    private String currentExecutionId;

    // ==================== 变量自动补全 (Task 1) ====================
    /** 变量补全弹窗 */
    private Popup variablePopup;
    /** 补全列表视图 */
    private ListView<String> variableListView;
    /** 当前补全目标输入控件(URL 输入框或 raw body 编辑区) */
    private TextInputControl completionTarget;
    /** 触发补全的 {{ 起始位置 */
    private int completionOpenIdx = -1;

    public RequestBuilderView(HttpEngineService httpEngine,
                              VariableResolver variableResolver,
                              InheritanceMerger inheritanceMerger,
                              CollectionService collectionService,
                              HistoryService historyService,
                              EnvironmentService environmentService,
                              DefaultHeaderProvider defaultHeaderProvider) {
        this.httpEngine = httpEngine;
        this.variableResolver = variableResolver;
        this.inheritanceMerger = inheritanceMerger;
        this.collectionService = collectionService;
        this.historyService = historyService;
        this.environmentService = environmentService;
        this.defaultHeaderProvider = defaultHeaderProvider;
        buildView();
    }

    public void setResponseListener(ResponseListener listener) {
        this.responseListener = listener;
    }

    public void setStatusUpdater(Consumer<String> updater) {
        this.statusUpdater = updater;
    }

    /** 返回根布局 */
    public VBox getRoot() {
        return root;
    }

    // ==================== RequestSelectionListener ====================

    @Override
    public void onRequestSelected(Collection collection, RequestNode request) {
        this.currentCollection = collection;
        this.currentRequest = request;

        // 使用 InheritanceMerger 合并集合继承
        RequestConfig merged = inheritanceMerger.merge(request, collection);

        // 填充 UI
        Platform.runLater(() -> loadConfigToUI(merged));
    }

    // ==================== UI 构建 ====================

    private void buildView() {
        root = new VBox(0);
        root.getStyleClass().add("request-builder");

        // 顶部请求栏
        root.getChildren().add(buildRequestBar());

        // 标签页区域
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("request-tabs");

        Tab paramsTab = new Tab("参数");
        paramsTab.setContent(buildParamsTab());

        Tab headersTab = new Tab("请求头");
        headersTab.setContent(buildHeadersTab());

        Tab bodyTab = new Tab("请求体");
        bodyTab.setContent(buildBodyTab());

        Tab authTab = new Tab("认证");
        authTab.setContent(buildAuthTab());

        tabPane.getTabs().addAll(paramsTab, headersTab, bodyTab, authTab);

        VBox.setVgrow(tabPane, Priority.ALWAYS);
        root.getChildren().add(tabPane);

        // 初始化自动生成头展示
        updateAutoHeaders();
        // 初始化变量自动补全弹窗 (Task 1)
        initVariableCompletion();
    }

    /** 顶部请求栏: Method + URL + Send/Cancel */
    private HBox buildRequestBar() {
        HBox bar = new HBox(8);
        bar.getStyleClass().add("request-bar");
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.CENTER_LEFT);

        methodCombo = new ComboBox<>(FXCollections.observableArrayList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
        methodCombo.setValue("GET");
        methodCombo.setEditable(true);
        methodCombo.getStyleClass().add("method-combo");
        methodCombo.setPrefWidth(100);

        urlField = new TextField();
        urlField.setPromptText("输入请求 URL (如 https://api.example.com/users)");
        urlField.getStyleClass().add("url-field");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        // Fix 1: URL 失焦时解析查询参数填充到 Params 表格
        urlField.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) syncUrlToParams();
        });
        // Task 1: URL 输入框变量自动补全
        setupVariableCompletion(urlField);

        sendButton = new Button("发送");
        sendButton.getStyleClass().add("send-button");
        sendButton.setOnAction(e -> sendRequest());

        // Task 2: 保存请求按钮 — 保存到当前集合
        saveButton = new Button("保存");
        saveButton.getStyleClass().add("save-button");
        saveButton.setTooltip(new Tooltip("保存请求到当前集合"));
        saveButton.setOnAction(e -> saveCurrentRequest());

        cancelButton = new Button("取消");
        cancelButton.getStyleClass().add("cancel-button");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> cancelRequest());

        bar.getChildren().addAll(methodCombo, urlField, sendButton, saveButton, cancelButton);
        return bar;
    }

    // ==================== Params 标签页 ====================

    private VBox buildParamsTab() {
        VBox container = createTabContainer();

        // Fix 1: Params 表格内容变化时反向更新 URL
        paramsData.addListener((ListChangeListener<KeyValueRow>) c -> syncParamsToUrl());

        paramsTable = createKeyValueTable(paramsData, "查询参数", this::syncParamsToUrl, false);
        // Task 3: 右键复制菜单
        attachCopyContextMenu(paramsTable);

        Button addBtn = new Button("+ 添加参数");
        addBtn.setOnAction(e -> paramsData.add(new KeyValueRow("", "", true, "")));

        Button removeBtn = new Button("- 删除选中");
        removeBtn.setOnAction(e -> paramsData.removeAll(paramsTable.getSelectionModel().getSelectedItems()));

        container.getChildren().addAll(createToolbar(addBtn, removeBtn), paramsTable);
        return container;
    }

    // ==================== Headers 标签页 ====================

    private VBox buildHeadersTab() {
        VBox container = createTabContainer();

        // Fix 2: Headers 表格(使用过滤视图,Key 列带下拉自动补全)
        headersTable = createKeyValueTable(headersFiltered, "请求头", null, true);
        // Task 3: 右键复制菜单
        attachCopyContextMenu(headersTable);

        Button addBtn = new Button("+ 添加请求头");
        addBtn.setOnAction(e -> headersData.add(new KeyValueRow("", "", true, "")));

        Button removeBtn = new Button("- 删除选中");
        removeBtn.setOnAction(e -> headersData.removeAll(headersTable.getSelectionModel().getSelectedItems()));

        // Fix 3: 切换是否显示自动生成的头
        ToggleButton showAutoBtn = new ToggleButton("显示自动头");
        showAutoBtn.setSelected(showAutoHeaders);
        showAutoBtn.selectedProperty().addListener((obs, o, n) -> {
            showAutoHeaders = n;
            autoHeadersBox.setVisible(n);
            autoHeadersBox.setManaged(n);
        });

        // Fix 3: 自动生成头只读区域(位于表格上方)
        autoHeadersBox = new VBox(2);
        autoHeadersBox.setPadding(new Insets(6));
        autoHeadersBox.getStyleClass().add("auto-headers-box");
        autoHeadersBox.setVisible(showAutoHeaders);
        autoHeadersBox.setManaged(showAutoHeaders);

        container.getChildren().addAll(
                createToolbar(addBtn, removeBtn, showAutoBtn),
                autoHeadersBox,
                headersTable);
        return container;
    }

    /**
     * Task 5: 更新自动生成的头(可交互)区域。
     * <p>
     * 委托 {@link DefaultHeaderProvider#computeDisplayAutoHeaders} 计算自动头列表
     * (5 个静态默认头 + Body 推导的 Content-Type + Auth 派生头)。
     * <p>
     * 每个自动头一行: 左侧 CheckBox(默认选中,取消勾选则该自动头不发送),
     * 中间显示 "Key: Value"。CheckBox 状态由 {@link #disabledAutoHeaderKeys} 驱动,
     * 重建时按其中的小写 key 设置为未勾选。
     */
    private void updateAutoHeaders() {
        if (autoHeadersBox == null) return;
        autoHeadersBox.getChildren().clear();

        Label title = new Label("自动生成的头(取消勾选则不发送):");
        title.setStyle("-fx-font-weight: bold;");
        autoHeadersBox.getChildren().add(title);

        if (defaultHeaderProvider == null) {
            autoHeadersBox.getChildren().add(new Label("(DefaultHeaderProvider 未初始化)"));
            return;
        }

        String bodyType = bodyTypeCombo != null ? bodyTypeCombo.getValue() : "none";
        // 解析 raw 模式对应的完整 Content-Type(非 raw 返回 null)
        String rawContentType = null;
        if ("raw".equals(bodyType)) {
            RequestBody tempBody = new RequestBody();
            tempBody.setType("raw");
            tempBody.setRawType(rawTypeCombo != null ? rawTypeCombo.getValue() : "text");
            rawContentType = defaultHeaderProvider.resolveRawContentType(tempBody);
        }

        AuthConfig auth = collectAuth();
        List<Header> autoHeaders = defaultHeaderProvider.computeDisplayAutoHeaders(bodyType, rawContentType, auth);

        if (autoHeaders == null || autoHeaders.isEmpty()) {
            autoHeadersBox.getChildren().add(new Label("(无自动生成的头)"));
            return;
        }

        for (Header h : autoHeaders) {
            String key = h.getKey() != null ? h.getKey() : "";
            String value = h.getValue() != null ? h.getValue() : "";
            String lowerKey = key.trim().toLowerCase();

            CheckBox checkBox = new CheckBox();
            // 默认选中; 若该 key 在 disabledAutoHeaderKeys 中则未勾选
            checkBox.setSelected(!disabledAutoHeaderKeys.contains(lowerKey));
            // 取消勾选 -> 加入禁用集合; 重新勾选 -> 从禁用集合移除
            final String fk = lowerKey;
            checkBox.setOnAction(e -> {
                if (checkBox.isSelected()) {
                    disabledAutoHeaderKeys.remove(fk);
                } else {
                    disabledAutoHeaderKeys.add(fk);
                }
            });

            Label label = new Label(key + ": " + value);
            HBox row = new HBox(8, checkBox, label);
            row.setAlignment(Pos.CENTER_LEFT);
            autoHeadersBox.getChildren().add(row);
        }
    }

    /**
     * 计算给定配置在保存时对应的自动头 key 集合(小写)。
     * <p>
     * 用于 loadConfigToUI 时区分"取消自动头"的 disabled 行与普通用户头:
     * disabled 且 key 命中此集合的行会被还原到 {@link #disabledAutoHeaderKeys},
     * 而不是作为 disabled 行放入用户头表格(避免污染表格并造成 CheckBox 状态不一致)。
     */
    private Set<String> computeAutoHeaderKeysLower(RequestConfig config) {
        Set<String> keys = new HashSet<>();
        if (defaultHeaderProvider == null || config == null) {
            return keys;
        }
        RequestBody body = config.getBody();
        String bodyType = body != null ? body.getType() : null;
        String rawCt = defaultHeaderProvider.resolveRawContentType(body);
        AuthConfig auth = config.getAuth() != null ? config.getAuth() : AuthConfig.none();
        List<Header> autoHeaders = defaultHeaderProvider.computeDisplayAutoHeaders(bodyType, rawCt, auth);
        if (autoHeaders != null) {
            for (Header h : autoHeaders) {
                if (h.getKey() != null && !h.getKey().trim().isEmpty()) {
                    keys.add(h.getKey().trim().toLowerCase());
                }
            }
        }
        return keys;
    }

    // ==================== Body 标签页 ====================

    private VBox buildBodyTab() {
        VBox container = createTabContainer();

        HBox typeBar = new HBox(8);
        typeBar.setPadding(new Insets(4, 0, 8, 0));
        typeBar.getChildren().add(new Label("Body 类型:"));
        bodyTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "none", "form-data", "x-www-form-urlencoded", "raw", "binary"));
        bodyTypeCombo.setValue("none");
        bodyTypeCombo.setCellFactory(lv -> mappingCell(this::bodyTypeDisplay));
        bodyTypeCombo.setButtonCell(mappingCell(this::bodyTypeDisplay));
        bodyTypeCombo.setOnAction(e -> switchBodyType(bodyTypeCombo.getValue()));
        typeBar.getChildren().add(bodyTypeCombo);

        bodyContent = new VBox(8);

        // raw 编辑器
        rawTypeCombo = new ComboBox<>(FXCollections.observableArrayList("json", "xml", "html", "text"));
        rawTypeCombo.setValue("json");
        rawTypeCombo.setCellFactory(lv -> mappingCell(this::rawTypeDisplay));
        rawTypeCombo.setButtonCell(mappingCell(this::rawTypeDisplay));
        // Fix 3: raw 格式变化时刷新自动头
        rawTypeCombo.setOnAction(e -> updateAutoHeaders());

        rawBodyArea = new TextArea();
        rawBodyArea.setPromptText("输入请求体内容...");
        rawBodyArea.setWrapText(true);
        rawBodyArea.setPrefRowCount(15);
        // Task 1: raw body 编辑区变量自动补全
        setupVariableCompletion(rawBodyArea);

        // form-data 表格(Fix 6: type 可切换, file 读取 Base64)
        formDataTable = new TableView<>();
        setupFormDataTable();

        // 初始显示 none
        switchBodyType("none");

        container.getChildren().addAll(typeBar, bodyContent);
        return container;
    }

    /** Fix 6: form-data 表格列配置(enabled 在前、Key、Type 下拉、Value 自适应文本/文件) */
    private void setupFormDataTable() {
        formDataTable.setEditable(true);

        // Enabled 列(第一列,无表头文字,窄)
        TableColumn<FormDataRow, Boolean> enabledCol = new TableColumn<>("");
        enabledCol.setCellValueFactory(p -> p.getValue().enabledProperty());
        enabledCol.setCellFactory(col -> createEnabledCell(null));
        enabledCol.setPrefWidth(30);
        enabledCol.setMinWidth(30);
        enabledCol.setMaxWidth(30);

        // Key 列(失焦确认)
        TableColumn<FormDataRow, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(p -> p.getValue().keyProperty());
        keyCol.setCellFactory(col -> createCommitOnFocusLostCell());
        keyCol.setPrefWidth(150);
        keyCol.setOnEditCommit(e -> e.getRowValue().setKey(e.getNewValue()));

        // Type 列(ComboBox: text / file)
        TableColumn<FormDataRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(p -> p.getValue().typeProperty());
        typeCol.setCellFactory(col -> createFormDataTypeCell());
        typeCol.setPrefWidth(90);
        typeCol.setOnEditCommit(e -> {
            e.getRowValue().setType(e.getNewValue());
            // 类型变化后刷新 Value 列显示(text ↔ file)
            formDataTable.refresh();
        });

        // Value 列(text 时可编辑,file 时显示文件名并打开选择器)
        TableColumn<FormDataRow, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(p -> p.getValue().valueProperty());
        valueCol.setCellFactory(col -> createFormDataValueCell());
        valueCol.setPrefWidth(280);
        valueCol.setOnEditCommit(e -> e.getRowValue().setValue(e.getNewValue()));

        formDataTable.getColumns().addAll(enabledCol, keyCol, typeCol, valueCol);
        formDataTable.setItems(formDataData);
        formDataTable.setPrefHeight(300);
    }

    private void switchBodyType(String type) {
        bodyContent.getChildren().clear();
        if (type == null) type = "none";
        switch (type) {
            case "raw" -> {
                HBox rawBar = new HBox(8);
                rawBar.getChildren().addAll(new Label("格式:"), rawTypeCombo);
                bodyContent.getChildren().addAll(rawBar, rawBodyArea);
            }
            case "form-data" -> {
                VBox formBox = new VBox(8);
                // Fix 6: 统一用 "+ 添加" 按钮,添加后可切换 type
                Button addBtn = new Button("+ 添加");
                addBtn.setOnAction(e -> formDataData.add(new FormDataRow("", "text", "", true)));
                Button removeBtn = new Button("- 删除选中");
                removeBtn.setOnAction(e -> formDataData.removeAll(formDataTable.getSelectionModel().getSelectedItems()));
                formBox.getChildren().addAll(createToolbar(addBtn, removeBtn), formDataTable);
                bodyContent.getChildren().add(formBox);
            }
            case "x-www-form-urlencoded" -> {
                VBox formBox = new VBox(8);
                Button addBtn = new Button("+ 添加");
                addBtn.setOnAction(e -> formDataData.add(new FormDataRow("", "text", "", true)));
                Button removeBtn = new Button("- 删除选中");
                removeBtn.setOnAction(e -> formDataData.removeAll(formDataTable.getSelectionModel().getSelectedItems()));
                formBox.getChildren().addAll(createToolbar(addBtn, removeBtn), formDataTable);
                bodyContent.getChildren().add(formBox);
            }
            case "binary" -> {
                HBox binaryBox = new HBox(8);
                binaryBox.setAlignment(Pos.CENTER_LEFT);
                Button chooseFileBtn = new Button("选择文件...");
                chooseFileBtn.setOnAction(e -> chooseBinaryFile());
                binaryFileLabel = new Label(binaryFileName != null ? binaryFileName : "未选择文件");
                binaryBox.getChildren().addAll(chooseFileBtn, binaryFileLabel);
                bodyContent.getChildren().add(binaryBox);
            }
            default -> {
                Label hint = new Label("该请求没有请求体");
                hint.setOpacity(0.5);
                bodyContent.getChildren().add(hint);
            }
        }
        // Fix 3: body 类型变化时刷新自动头
        updateAutoHeaders();
    }

    /** Fix 6: binary 文件选择 — 读取为 Base64 存储,仅显示文件名 */
    private void chooseBinaryFile() {
        FileChooser fc = new FileChooser();
        File file = fc.showOpenDialog(root.getScene().getWindow());
        if (file != null) {
            try {
                binaryFileContent = readFileAsBase64(file);
                binaryFileName = file.getName();
                binaryFilePath = file.getAbsolutePath();
                if (binaryFileLabel != null) {
                    binaryFileLabel.setText(binaryFileName);
                }
            } catch (Exception ex) {
                updateStatus("读取文件失败: " + ex.getMessage());
            }
        }
    }

    // ==================== Auth 标签页 ====================

    private VBox buildAuthTab() {
        VBox container = createTabContainer();

        HBox typeBar = new HBox(8);
        typeBar.setPadding(new Insets(4, 0, 8, 0));
        typeBar.getChildren().add(new Label("认证类型:"));
        authTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "none", "basic", "bearer", "apikey", "inherit"));
        authTypeCombo.setValue("none");
        authTypeCombo.setCellFactory(lv -> mappingCell(this::authTypeDisplay));
        authTypeCombo.setButtonCell(mappingCell(this::authTypeDisplay));
        authTypeCombo.setOnAction(e -> switchAuthType(authTypeCombo.getValue()));
        typeBar.getChildren().add(authTypeCombo);

        authContent = new VBox(8);
        switchAuthType("none");

        container.getChildren().addAll(typeBar, authContent);
        return container;
    }

    private void switchAuthType(String type) {
        authContent.getChildren().clear();
        if (type == null) type = "none";
        switch (type) {
            case "basic" -> {
                basicUsername = new TextField();
                basicUsername.setPromptText("用户名");
                basicPassword = new PasswordField();
                basicPassword.setPromptText("密码");
                // Fix 3: 凭据变化时刷新自动头
                basicUsername.textProperty().addListener((obs, o, n) -> updateAutoHeaders());
                basicPassword.textProperty().addListener((obs, o, n) -> updateAutoHeaders());
                authContent.getChildren().addAll(
                        new Label("用户名:"), basicUsername,
                        new Label("密码:"), basicPassword);
            }
            case "bearer" -> {
                bearerToken = new TextField();
                bearerToken.setPromptText("Bearer Token");
                // Fix 3: token 变化时刷新自动头
                bearerToken.textProperty().addListener((obs, o, n) -> updateAutoHeaders());
                authContent.getChildren().addAll(new Label("Token:"), bearerToken);
            }
            case "apikey" -> {
                apiKeyField = new TextField();
                apiKeyField.setPromptText("Header / Query 参数名 (如 X-API-Key)");
                apiKeyValue = new TextField();
                apiKeyValue.setPromptText("API Key 值");
                apiKeyAddTo = new ComboBox<>(FXCollections.observableArrayList("header", "query"));
                apiKeyAddTo.setValue("header");
                authContent.getChildren().addAll(
                        new Label("Key:"), apiKeyField,
                        new Label("Value:"), apiKeyValue,
                        new Label("添加到:"), apiKeyAddTo);
            }
            case "inherit" -> {
                Label hint = new Label("继承集合级认证配置");
                hint.setOpacity(0.6);
                authContent.getChildren().add(hint);
            }
            default -> {
                Label hint = new Label("该请求不需要认证");
                hint.setOpacity(0.5);
                authContent.getChildren().add(hint);
            }
        }
        // Fix 3: auth 类型变化时刷新自动头
        updateAutoHeaders();
    }

    // ==================== URL ↔ Params 同步 (Fix 1) ====================

    /** 解析 URL 查询字符串,填充到 Params 表格(保留同 key 的 description) */
    private void syncUrlToParams() {
        syncingUrlParams = true;
        try {
            String url = urlField.getText();
            if (url == null || url.isEmpty()) {
                paramsData.clear();
                return;
            }
            int qIdx = url.indexOf('?');
            if (qIdx < 0) {
                paramsData.clear();
                return;
            }
            String query = url.substring(qIdx + 1);
            // 去掉可能的 #fragment
            int hashIdx = query.indexOf('#');
            if (hashIdx >= 0) query = query.substring(0, hashIdx);

            // 保留现有 description 映射,避免同步时丢失
            Map<String, String> descMap = new HashMap<>();
            for (KeyValueRow r : paramsData) {
                if (r.getKey() != null && !r.getKey().isEmpty()) {
                    descMap.put(r.getKey(), r.getDescription());
                }
            }

            paramsData.clear();
            if (query.isEmpty()) return;
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k;
                String v;
                if (eq > 0) {
                    k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } else {
                    k = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                    v = "";
                }
                String desc = descMap.getOrDefault(k, "");
                paramsData.add(new KeyValueRow(k, v, true, desc));
            }
        } finally {
            syncingUrlParams = false;
        }
    }

    /** 根据启用的 Params 行反向构建 URL 查询字符串 */
    private void syncParamsToUrl() {
        if (syncingUrlParams) return;
        String url = urlField.getText();
        String baseUrl;
        String fragment = "";
        if (url == null) {
            url = "";
            baseUrl = "";
        } else {
            int qIdx = url.indexOf('?');
            int hashIdx = url.indexOf('#');
            int cut = qIdx;
            if (hashIdx >= 0 && (qIdx < 0 || hashIdx < qIdx)) {
                cut = hashIdx;
            }
            if (cut >= 0) {
                baseUrl = url.substring(0, cut);
                if (hashIdx >= 0) {
                    fragment = url.substring(hashIdx);
                }
            } else {
                baseUrl = url;
            }
        }

        StringBuilder sb = new StringBuilder(baseUrl);
        List<KeyValueRow> enabled = paramsData.stream()
                .filter(r -> r.isEnabled() && r.getKey() != null && !r.getKey().isEmpty())
                .toList();
        if (!enabled.isEmpty()) {
            sb.append("?");
            for (int i = 0; i < enabled.size(); i++) {
                if (i > 0) sb.append("&");
                sb.append(URLEncoder.encode(enabled.get(i).getKey(), StandardCharsets.UTF_8));
                sb.append("=");
                String val = enabled.get(i).getValue();
                sb.append(URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8));
            }
        }
        if (!fragment.isEmpty()) {
            sb.append(fragment);
        }
        urlField.setText(sb.toString());
    }

    // ==================== 请求加载与发送 ====================

    /** 从历史记录加载请求配置到 UI */
    public void loadFromHistory(com.jcurl.model.HistoryRecord record) {
        this.currentRequest = null; // 历史记录不关联集合树节点
        this.currentCollection = null;

        RequestConfig config = new RequestConfig();
        config.setMethod(record.getMethod());
        config.setUrl(record.getUrl());
        config.setParams(record.getParams() != null ? record.getParams() : new ArrayList<>());
        config.setHeaders(record.getHeaders() != null ? record.getHeaders() : new ArrayList<>());
        config.setBody(record.getBody() != null ? record.getBody() : new RequestBody());
        config.setAuth(record.getAuth() != null ? record.getAuth() : AuthConfig.none());

        Platform.runLater(() -> loadConfigToUI(config));
    }

    /** 将合并后的 RequestConfig 加载到 UI */
    private void loadConfigToUI(RequestConfig config) {
        methodCombo.setValue(config.getMethod());
        urlField.setText(config.getUrl());

        // Params(加载时禁止反向同步,避免覆盖 URL)
        syncingUrlParams = true;
        try {
            paramsData.clear();
            for (QueryParam p : config.getParams()) {
                paramsData.add(new KeyValueRow(p.getKey(), p.getValue(), p.isEnabled(), p.getDescription()));
            }
        } finally {
            syncingUrlParams = false;
        }

        // Headers(只加载用户头;disabled 且 key 命中自动头的行还原为 autoHeadersBox 取消勾选状态)
        headersData.clear();
        disabledAutoHeaderKeys.clear();
        Set<String> savedAutoKeys = computeAutoHeaderKeysLower(config);
        for (Header h : config.getHeaders()) {
            String key = h.getKey();
            if (key == null || key.isBlank()) {
                continue; // 跳过空 key 行
            }
            String lowerKey = key.trim().toLowerCase();
            if (!h.isEnabled() && savedAutoKeys.contains(lowerKey)) {
                // 该 disabled 行代表"取消自动头",还原到 disabledAutoHeaderKeys,不放入用户头表格
                disabledAutoHeaderKeys.add(lowerKey);
            } else {
                headersData.add(new KeyValueRow(key, h.getValue(), h.isEnabled(), h.getDescription()));
            }
        }

        // Body
        RequestBody body = config.getBody();
        if (body != null) {
            bodyTypeCombo.setValue(body.getType());
            // 先加载 binary 文件信息,以便 switchBodyType 显示正确的文件名
            if ("binary".equals(body.getType())) {
                binaryFilePath = body.getFilePath();
                binaryFileContent = body.getFileContent();
                binaryFileName = body.getFileName();
            }
            switchBodyType(body.getType());
            if ("raw".equals(body.getType())) {
                rawTypeCombo.setValue(body.getRawType() != null ? body.getRawType() : "text");
                rawBodyArea.setText(body.getContent() != null ? body.getContent() : "");
            } else if ("form-data".equals(body.getType()) || "x-www-form-urlencoded".equals(body.getType())) {
                formDataData.clear();
                for (FormItem item : body.getFormItems()) {
                    FormDataRow row = new FormDataRow(
                            item.getKey(),
                            "file".equals(item.getType()) ? "file" : "text",
                            "", item.isEnabled());
                    if ("file".equals(item.getType())) {
                        row.setFileContent(item.getFileContent() != null ? item.getFileContent() : "");
                        row.setFileName(item.getFileName() != null ? item.getFileName() : "");
                    } else {
                        row.setValue(item.getValue() != null ? item.getValue() : "");
                    }
                    formDataData.add(row);
                }
            }
        }

        // Auth
        AuthConfig auth = config.getAuth();
        if (auth != null) {
            authTypeCombo.setValue(auth.getType());
            switchAuthType(auth.getType());
            switch (auth.getType()) {
                case "basic" -> {
                    if (basicUsername != null) basicUsername.setText(auth.getUsername() != null ? auth.getUsername() : "");
                    if (basicPassword != null) basicPassword.setText(auth.getPassword() != null ? auth.getPassword() : "");
                }
                case "bearer" -> {
                    if (bearerToken != null) bearerToken.setText(auth.getToken() != null ? auth.getToken() : "");
                }
                case "apikey" -> {
                    if (apiKeyField != null) apiKeyField.setText(auth.getKey() != null ? auth.getKey() : "");
                    if (apiKeyValue != null) apiKeyValue.setText(auth.getValue() != null ? auth.getValue() : "");
                    if (apiKeyAddTo != null) apiKeyAddTo.setValue(auth.getAddTo() != null ? auth.getAddTo() : "header");
                }
            }
        }

        updateAutoHeaders();
        updateStatus("已加载请求: " + (currentRequest != null ? currentRequest.getName() : ""));
    }

    /** 获取当前 UI 上的请求配置(供性能测试等模块使用) */
    public RequestConfig getCurrentConfig() {
        return collectConfig();
    }

    /** 从 UI 收集 RequestConfig */
    private RequestConfig collectConfig() {
        RequestConfig config = new RequestConfig();
        config.setMethod(methodCombo.getValue());
        config.setUrl(urlField.getText());

        // Params
        for (KeyValueRow row : paramsData) {
            if (row.getKey() != null && !row.getKey().isBlank()) {
                QueryParam p = new QueryParam();
                p.setKey(row.getKey());
                p.setValue(row.getValue());
                p.setEnabled(row.isEnabled());
                p.setDescription(row.getDescription());
                config.getParams().add(p);
            }
        }

        // Headers
        // 先收集用户头(Headers 表格),记录已存在的 key(小写)以避免与"取消自动头"的 disabled 行冲突
        Set<String> userHeaderKeysLower = new HashSet<>();
        for (KeyValueRow row : headersData) {
            if (row.getKey() != null && !row.getKey().isBlank()) {
                Header h = new Header();
                h.setKey(row.getKey());
                h.setValue(row.getValue());
                h.setEnabled(row.isEnabled());
                h.setDescription(row.getDescription());
                config.getHeaders().add(h);
                userHeaderKeysLower.add(row.getKey().trim().toLowerCase());
            }
        }
        // 将用户在 autoHeadersBox 取消勾选的自动头作为 disabled Header 加入,
        // 使 mergeEffective 移除对应的自动头;已由用户头覆盖的 key 不重复添加
        for (String key : disabledAutoHeaderKeys) {
            if (key == null || key.isEmpty()) continue;
            if (!userHeaderKeysLower.contains(key)) {
                Header h = new Header();
                h.setKey(key);
                h.setValue("");
                h.setEnabled(false);
                h.setDescription("取消自动头");
                config.getHeaders().add(h);
            }
        }

        // Body
        RequestBody body = new RequestBody();
        body.setType(bodyTypeCombo.getValue());
        if ("raw".equals(body.getType())) {
            body.setRawType(rawTypeCombo.getValue());
            body.setContent(rawBodyArea.getText());
        } else if ("form-data".equals(body.getType()) || "x-www-form-urlencoded".equals(body.getType())) {
            for (FormDataRow row : formDataData) {
                if (row.getKey() == null || row.getKey().isBlank()) continue;
                FormItem item = new FormItem();
                item.setKey(row.getKey());
                item.setType("file".equals(row.getType()) ? "file" : "text");
                if ("file".equals(row.getType())) {
                    // Fix 6: file 类型存储 Base64 内容与文件名
                    item.setFileContent(row.getFileContent());
                    item.setFileName(row.getFileName());
                    item.setFilePath(null);
                } else {
                    item.setValue(row.getValue());
                }
                item.setEnabled(row.isEnabled());
                body.getFormItems().add(item);
            }
        } else if ("binary".equals(body.getType())) {
            // Fix 6: binary 存储 Base64 内容与文件名
            body.setFilePath(binaryFilePath);
            body.setFileContent(binaryFileContent);
            body.setFileName(binaryFileName);
        }
        config.setBody(body);

        // Auth
        config.setAuth(collectAuth());

        return config;
    }

    /** 从 UI 收集当前认证配置(供 collectConfig 与 updateAutoHeaders 共用) */
    private AuthConfig collectAuth() {
        AuthConfig auth = new AuthConfig();
        auth.setType(authTypeCombo != null ? authTypeCombo.getValue() : "none");
        if (auth.getType() == null) auth.setType("none");
        switch (auth.getType()) {
            case "basic" -> {
                auth.setUsername(basicUsername != null ? basicUsername.getText() : "");
                auth.setPassword(basicPassword != null ? basicPassword.getText() : "");
            }
            case "bearer" -> auth.setToken(bearerToken != null ? bearerToken.getText() : "");
            case "apikey" -> {
                auth.setKey(apiKeyField != null ? apiKeyField.getText() : "");
                auth.setValue(apiKeyValue != null ? apiKeyValue.getText() : "");
                auth.setAddTo(apiKeyAddTo != null ? apiKeyAddTo.getValue() : "header");
            }
        }
        return auth;
    }

    /** 触发送请求(供快捷键调用) */
    public void triggerSend() {
        sendRequest();
    }

    /** 聚焦 URL 输入框 */
    public void focusUrlField() {
        urlField.requestFocus();
        urlField.selectAll();
    }

    /** 发送请求 */
    private void sendRequest() {
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            updateStatus("错误: URL 不能为空");
            return;
        }

        // 收集配置
        RequestConfig config = collectConfig();

        // 解析变量: 构建完整的 VariableScope(环境变量 + 集合变量 + 全局变量)
        VariableScope scope = environmentService.buildScope(currentCollection);
        resolveVariables(config, scope);

        // 生成 executionId
        currentExecutionId = UUID.randomUUID().toString();

        // UI 状态切换
        sendButton.setDisable(true);
        cancelButton.setDisable(false);
        updateStatus("正在发送请求...");

        // 异步执行
        httpEngine.executeAsync(config, currentExecutionId)
                .thenAccept(response -> {
                    // 记录历史
                    try {
                        historyService.record(config, response);
                    } catch (Exception e) {
                        log.warn("记录历史失败", e);
                    }

                    // 通知响应监听器
                    if (responseListener != null) {
                        Platform.runLater(() -> responseListener.onResponse(response));
                    }

                    // 恢复 UI 状态
                    Platform.runLater(() -> {
                        sendButton.setDisable(false);
                        cancelButton.setDisable(true);
                        updateStatus(String.format("响应: %d %s (%dms)",
                                response.getStatusCode(),
                                response.getStatusText() != null ? response.getStatusText() : "",
                                response.getTiming() != null ? response.getTiming().getTotalMs() : 0));
                    });
                })
                .exceptionally(throwable -> {
                    Platform.runLater(() -> {
                        sendButton.setDisable(false);
                        cancelButton.setDisable(true);
                        updateStatus("请求异常: " + throwable.getMessage());
                    });
                    return null;
                });
    }

    /** 取消当前请求 */
    private void cancelRequest() {
        if (currentExecutionId != null) {
            boolean cancelled = httpEngine.cancel(currentExecutionId);
            if (cancelled) {
                updateStatus("请求已取消");
            }
            sendButton.setDisable(false);
            cancelButton.setDisable(true);
        }
    }

    // ==================== 保存请求 (Task 2) ====================

    /**
     * Task 2: 保存当前请求到所属集合。
     * <p>
     * currentRequest 不为 null 时,将 UI 配置写回请求节点并调用
     * {@link CollectionService#saveCollection} 持久化;为 null(临时请求)时
     * 提示用户先从集合树选择请求或新建请求。
     */
    private void saveCurrentRequest() {
        if (currentRequest == null || currentCollection == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("保存请求");
            alert.setHeaderText(null);
            alert.setContentText("当前为临时请求,请先从集合树选择请求或新建请求后再保存。");
            alert.showAndWait();
            updateStatus("保存失败: 当前为临时请求");
            return;
        }
        RequestConfig config = collectConfig();
        applyConfigToNode(config, currentRequest);
        collectionService.saveCollection(currentCollection);
        updateStatus("已保存请求: " + currentRequest.getName());
    }

    /** 将 UI 收集的 RequestConfig 写回 RequestNode(深拷贝各字段,避免与 UI 共享引用) */
    private void applyConfigToNode(RequestConfig config, RequestNode node) {
        node.setMethod(config.getMethod());
        node.setUrl(config.getUrl());

        // Params
        node.getParams().clear();
        for (QueryParam p : config.getParams()) {
            QueryParam copy = new QueryParam();
            copy.setKey(p.getKey());
            copy.setValue(p.getValue());
            copy.setEnabled(p.isEnabled());
            copy.setDescription(p.getDescription());
            node.getParams().add(copy);
        }

        // Headers
        node.getHeaders().clear();
        for (Header h : config.getHeaders()) {
            Header copy = new Header();
            copy.setKey(h.getKey());
            copy.setValue(h.getValue());
            copy.setEnabled(h.isEnabled());
            copy.setDescription(h.getDescription());
            node.getHeaders().add(copy);
        }

        // Body(深拷贝)
        RequestBody src = config.getBody();
        RequestBody body = new RequestBody();
        body.setType(src.getType());
        body.setContent(src.getContent());
        body.setRawType(src.getRawType());
        body.setFilePath(src.getFilePath());
        body.setFileContent(src.getFileContent());
        body.setFileName(src.getFileName());
        if (src.getFormItems() != null) {
            for (FormItem item : src.getFormItems()) {
                body.getFormItems().add(item);
            }
        }
        node.setBody(body);

        // Auth(深拷贝)
        AuthConfig a = config.getAuth();
        AuthConfig auth = new AuthConfig();
        auth.setType(a.getType());
        auth.setUsername(a.getUsername());
        auth.setPassword(a.getPassword());
        auth.setToken(a.getToken());
        auth.setKey(a.getKey());
        auth.setValue(a.getValue());
        auth.setAddTo(a.getAddTo());
        node.setAuth(auth);
    }

    // ==================== 工具方法 ====================

    /** 解析 RequestConfig 中所有字符串字段的环境变量 */
    private void resolveVariables(RequestConfig config, VariableScope scope) {
        // URL
        config.setUrl(variableResolver.resolve(config.getUrl(), scope));

        // Params
        for (QueryParam p : config.getParams()) {
            p.setKey(variableResolver.resolve(p.getKey(), scope));
            p.setValue(variableResolver.resolve(p.getValue(), scope));
        }

        // Headers
        for (Header h : config.getHeaders()) {
            h.setKey(variableResolver.resolve(h.getKey(), scope));
            h.setValue(variableResolver.resolve(h.getValue(), scope));
        }

        // Body
        RequestBody body = config.getBody();
        if (body != null) {
            if ("raw".equals(body.getType()) && body.getContent() != null) {
                body.setContent(variableResolver.resolve(body.getContent(), scope));
            }
            for (FormItem item : body.getFormItems()) {
                item.setKey(variableResolver.resolve(item.getKey(), scope));
                if (item.getValue() != null) {
                    item.setValue(variableResolver.resolve(item.getValue(), scope));
                }
            }
        }

        // Auth
        AuthConfig auth = config.getAuth();
        if (auth != null) {
            if (auth.getUsername() != null) auth.setUsername(variableResolver.resolve(auth.getUsername(), scope));
            if (auth.getPassword() != null) auth.setPassword(variableResolver.resolve(auth.getPassword(), scope));
            if (auth.getToken() != null) auth.setToken(variableResolver.resolve(auth.getToken(), scope));
            if (auth.getKey() != null) auth.setKey(variableResolver.resolve(auth.getKey(), scope));
            if (auth.getValue() != null) auth.setValue(variableResolver.resolve(auth.getValue(), scope));
        }
    }

    private void updateStatus(String text) {
        if (statusUpdater != null) {
            Platform.runLater(() -> statusUpdater.accept(text));
        }
        log.info("Status: {}", text);
    }

    // ==================== 下拉框中文显示映射 ====================

    /**
     * 创建按映射函数显示文本的 ListCell。
     * <p>
     * 下拉框内部值保持英文(用于逻辑判断与持久化),仅 UI 显示中文,
     * getValue() 仍返回英文原始值,不影响 switchBodyType / collectConfig 等逻辑。
     */
    private ListCell<String> mappingCell(java.util.function.Function<String, String> mapper) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : mapper.apply(item));
            }
        };
    }

    /** Body 类型英文值 → 中文显示 */
    private String bodyTypeDisplay(String type) {
        if (type == null) return "";
        return switch (type) {
            case "none" -> "无";
            case "form-data" -> "表单数据";
            case "x-www-form-urlencoded" -> "URL编码表单";
            case "raw" -> "原始文本";
            case "binary" -> "二进制文件";
            default -> type;
        };
    }

    /** raw 格式英文值 → 中文显示 */
    private String rawTypeDisplay(String type) {
        if (type == null) return "";
        return switch (type) {
            case "json" -> "JSON";
            case "xml" -> "XML";
            case "html" -> "HTML";
            case "text" -> "Text";
            default -> type;
        };
    }

    /** Auth 类型英文值 → 中文显示 */
    private String authTypeDisplay(String type) {
        if (type == null) return "";
        return switch (type) {
            case "none" -> "无认证";
            case "basic" -> "Basic 认证";
            case "bearer" -> "Bearer Token";
            case "apikey" -> "API Key";
            case "inherit" -> "继承集合";
            default -> type;
        };
    }

    private VBox createTabContainer() {
        VBox container = new VBox(4);
        container.setPadding(new Insets(8));
        return container;
    }

    /** 灵活工具栏(可放置任意数量的按钮/开关) */
    private HBox createToolbar(Node... nodes) {
        HBox toolbar = new HBox(8);
        toolbar.setPadding(new Insets(4, 0, 4, 0));
        toolbar.getChildren().addAll(nodes);
        return toolbar;
    }

    /** Fix 6: 读取文件内容为 Base64 编码字符串 */
    private String readFileAsBase64(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Fix 6: form-data 文件选择 — 读取 Base64 存储,显示文件名 */
    private void chooseFormDataFile(FormDataRow row) {
        if (row == null) return;
        FileChooser fc = new FileChooser();
        File file = fc.showOpenDialog(root.getScene().getWindow());
        if (file != null) {
            try {
                row.setFileContent(readFileAsBase64(file));
                row.setFileName(file.getName());
                row.setValue("");
            } catch (Exception ex) {
                updateStatus("读取文件失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 创建可编辑的 Key-Value 表格(Params / Headers 共用)。
     * <p>
     * Fix 4: 所有文本列使用失焦自动确认的自定义 Cell。<br>
     * Fix 5: Enabled 勾选框列放在第一列,表头文字为空,列宽 30px。
     *
     * @param data             表格数据源(Headers 传入 FilteredList)
     * @param placeholder      空数据占位提示
     * @param onRowChange      行内容变化回调(Params 用于反向同步 URL)
     * @param useHeaderKeyCombo Key 列是否使用下拉自动补全(Headers)
     */
    private TableView<KeyValueRow> createKeyValueTable(ObservableList<KeyValueRow> data,
                                                       String placeholder,
                                                       Runnable onRowChange,
                                                       boolean useHeaderKeyCombo) {
        TableView<KeyValueRow> table = new TableView<>();
        table.setEditable(true);
        table.setPlaceholder(new Label(placeholder));
        // Task 4: 用 SortedList 包裹数据源,支持列点击排序(不改变底层数据顺序,
        // 因此排序不会反向覆盖 URL 查询串)
        SortedList<KeyValueRow> sortedData = new SortedList<>(data);
        sortedData.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sortedData);

        // Enabled 列(第一列,无表头文字,窄)
        TableColumn<KeyValueRow, Boolean> enabledCol = new TableColumn<>("");
        enabledCol.setCellValueFactory(p -> p.getValue().enabledProperty());
        enabledCol.setCellFactory(col -> createEnabledCell(onRowChange));
        enabledCol.setPrefWidth(30);
        enabledCol.setMinWidth(30);
        enabledCol.setMaxWidth(30);
        enabledCol.setSortable(true); // Task 4

        // Key 列
        TableColumn<KeyValueRow, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(p -> p.getValue().keyProperty());
        if (useHeaderKeyCombo) {
            // Fix 2: Headers Key 列带下拉自动补全
            keyCol.setCellFactory(col -> createHeaderKeyCell());
        } else {
            keyCol.setCellFactory(col -> createCommitOnFocusLostCell());
        }
        keyCol.setPrefWidth(180);
        keyCol.setSortable(true); // Task 4
        keyCol.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        keyCol.setOnEditCommit(e -> {
            e.getRowValue().setKey(e.getNewValue());
            if (onRowChange != null) onRowChange.run();
        });

        // Value 列
        TableColumn<KeyValueRow, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(p -> p.getValue().valueProperty());
        valueCol.setCellFactory(col -> createCommitOnFocusLostCell());
        valueCol.setPrefWidth(250);
        valueCol.setSortable(true); // Task 4
        valueCol.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        valueCol.setOnEditCommit(e -> {
            e.getRowValue().setValue(e.getNewValue());
            if (onRowChange != null) onRowChange.run();
        });

        // Description 列
        TableColumn<KeyValueRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(p -> p.getValue().descriptionProperty());
        descCol.setCellFactory(col -> createCommitOnFocusLostCell());
        descCol.setPrefWidth(200);
        descCol.setSortable(true); // Task 4
        descCol.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        descCol.setOnEditCommit(e -> {
            e.getRowValue().setDescription(e.getNewValue());
            if (onRowChange != null) onRowChange.run();
        });

        table.getColumns().addAll(enabledCol, keyCol, valueCol, descCol);
        table.setPrefHeight(300);
        return table;
    }

    // ==================== 右键复制 (Task 3) ====================

    /** 为 Key-Value 表格附加右键复制菜单(复制行 / 复制全部),复制到系统剪贴板 */
    private void attachCopyContextMenu(TableView<KeyValueRow> table) {
        ContextMenu menu = new ContextMenu();

        MenuItem copyRow = new MenuItem("复制行");
        copyRow.setOnAction(e -> {
            KeyValueRow row = table.getSelectionModel().getSelectedItem();
            if (row != null && row.getKey() != null) {
                String text = row.getKey() + "=" + (row.getValue() == null ? "" : row.getValue());
                copyToClipboard(text);
                updateStatus("已复制行: " + text);
            }
        });

        MenuItem copyAll = new MenuItem("复制全部");
        copyAll.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (KeyValueRow r : table.getItems()) {
                if (r.getKey() == null || r.getKey().isBlank()) continue;
                sb.append(r.getKey())
                  .append("=")
                  .append(r.getValue() == null ? "" : r.getValue())
                  .append("\n");
            }
            copyToClipboard(sb.toString().trim());
            updateStatus("已复制全部");
        });

        menu.getItems().addAll(copyRow, copyAll);
        table.setContextMenu(menu);
    }

    /** 复制文本到系统剪贴板 */
    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text != null ? text : "");
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ==================== 变量自动补全 (Task 1) ====================

    /** 初始化变量补全弹窗(URL 输入框与 raw body 编辑区共用) */
    private void initVariableCompletion() {
        variableListView = new ListView<>();
        variableListView.setPrefSize(300, 220);
        variableListView.getStyleClass().add("variable-completion-list");
        // 单击候选项即插入
        variableListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) {
                String selected = variableListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    insertVariableCompletion(selected);
                    e.consume();
                }
            }
        });
        variableListView.setOnKeyPressed(e -> {
            KeyCode code = e.getCode();
            if (code == KeyCode.ENTER) {
                String selected = variableListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    insertVariableCompletion(selected);
                }
                e.consume();
            } else if (code == KeyCode.ESCAPE) {
                hideVariablePopup();
                e.consume();
            }
        });

        variablePopup = new Popup();
        variablePopup.getContent().add(variableListView);
        variablePopup.setAutoHide(true);
        variablePopup.setHideOnEscape(true);
        variablePopup.setOnHidden(e -> {
            completionTarget = null;
            completionOpenIdx = -1;
        });
    }

    /**
     * 为指定输入控件(URL 输入框或 raw body 编辑区)绑定变量补全触发。
     * 当文本中出现未闭合的 {{ 时弹出补全列表;输入 }} 或按 Escape 关闭。
     */
    private void setupVariableCompletion(TextInputControl control) {
        control.textProperty().addListener((obs, old, newVal) -> handleVariableCompletion(control));
        // 在输入控件拥有焦点时拦截方向键/回车/Esc,用于操作补全列表
        control.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (variablePopup == null || !variablePopup.isShowing()) return;
            KeyCode code = e.getCode();
            if (code == KeyCode.ESCAPE) {
                hideVariablePopup();
                e.consume();
            } else if (code == KeyCode.ENTER) {
                String selected = variableListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    insertVariableCompletion(selected);
                }
                e.consume();
            } else if (code == KeyCode.UP || code == KeyCode.DOWN) {
                int size = variableListView.getItems().size();
                if (size > 0) {
                    int idx = variableListView.getSelectionModel().getSelectedIndex();
                    if (idx < 0) idx = 0;
                    idx = code == KeyCode.UP ? (idx - 1 + size) % size : (idx + 1) % size;
                    variableListView.getSelectionModel().select(idx);
                    variableListView.scrollTo(idx);
                    e.consume();
                }
            }
        });
    }

    /** 检测未闭合的 {{ 并刷新/隐藏补全弹窗 */
    private void handleVariableCompletion(TextInputControl control) {
        String text = control.getText();
        int caret = control.getCaretPosition();
        if (text == null || caret < 2) {
            hideVariablePopup();
            return;
        }
        String before = text.substring(0, caret);
        int openIdx = before.lastIndexOf("{{");
        if (openIdx < 0) {
            hideVariablePopup();
            return;
        }
        // {{ 之后到光标之间若已闭合(含 }}),则不弹
        String partial = before.substring(openIdx + 2);
        if (partial.contains("}}")) {
            hideVariablePopup();
            return;
        }
        completionTarget = control;
        completionOpenIdx = openIdx;
        showVariablePopup(control, partial);
    }

    /** 构建补全候选列表: 环境变量 + 全局变量 + 内置动态函数 */
    private List<String> buildVariableCandidates() {
        List<String> list = new ArrayList<>();

        // 当前环境变量
        try {
            Environment env = environmentService.getActiveEnvironment();
            if (env != null && env.getVariables() != null) {
                for (Variable v : env.getVariables()) {
                    if (v.isEnabled() && v.getKey() != null && !v.getKey().isBlank()
                            && !list.contains(v.getKey())) {
                        list.add(v.getKey());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载环境变量失败", e);
        }

        // 全局变量
        try {
            GlobalVariables globals = environmentService.loadGlobals();
            if (globals != null && globals.getVariables() != null) {
                for (Variable v : globals.getVariables()) {
                    if (v.isEnabled() && v.getKey() != null && !v.getKey().isBlank()
                            && !list.contains(v.getKey())) {
                        list.add(v.getKey());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载全局变量失败", e);
        }

        // 内置动态函数(以 $ 开头,与 VariableFunctionProvider 内置函数对应)
        list.add("$timestamp");
        list.add("$randomInt");
        list.add("$uuid");
        list.add("$datetime");

        return list;
    }

    /** 根据已输入的部分过滤并展示补全弹窗 */
    private void showVariablePopup(TextInputControl control, String partial) {
        List<String> candidates = buildVariableCandidates();
        String p = partial == null ? "" : partial.trim().toLowerCase();
        List<String> filtered;
        if (p.isEmpty()) {
            filtered = candidates;
        } else {
            final String key = p;
            filtered = candidates.stream()
                    .filter(c -> c.toLowerCase().contains(key))
                    .toList();
        }
        if (filtered.isEmpty()) {
            hideVariablePopup();
            return;
        }
        variableListView.getItems().setAll(filtered);
        variableListView.getSelectionModel().select(0);
        variableListView.scrollTo(0);

        // 定位弹窗到控件下方
        Bounds bounds = control.localToScreen(control.getBoundsInLocal());
        if (bounds != null && !variablePopup.isShowing()) {
            variablePopup.show(control, bounds.getMinX(), bounds.getMaxY());
        }
    }

    /** 隐藏补全弹窗 */
    private void hideVariablePopup() {
        if (variablePopup != null && variablePopup.isShowing()) {
            variablePopup.hide();
        }
        completionTarget = null;
        completionOpenIdx = -1;
    }

    /** 选中候选项后,将 {{partial 替换为 {{varName}} */
    private void insertVariableCompletion(String varName) {
        TextInputControl control = completionTarget;
        int openIdx = completionOpenIdx;
        if (control == null || openIdx < 0 || varName == null) {
            hideVariablePopup();
            return;
        }
        String text = control.getText();
        int caret = control.getCaretPosition();
        if (text == null || caret < openIdx + 2) {
            hideVariablePopup();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(text, 0, openIdx + 2);   // 保留 {{
        sb.append(varName);
        sb.append("}}");                    // 闭合
        sb.append(text.substring(caret));   // 光标之后内容
        control.setText(sb.toString());
        int newCaret = openIdx + 2 + varName.length() + 2;
        control.positionCaret(newCaret);
        hideVariablePopup();
    }

    // ==================== 自定义 Cell 工厂 ====================

    /**
     * Fix 4: 失焦自动确认输入的文本 Cell 工厂。
     * 替代 TextFieldTableCell,无需按回车即可确认。
     */
    private <S> TableCell<S, String> createCommitOnFocusLostCell() {
        return new TableCell<>() {
            private TextField textField;

            @Override
            public void startEdit() {
                super.startEdit();
                if (textField == null) {
                    textField = new TextField(getString());
                    textField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                        if (!newVal) commitEdit(textField.getText());
                    });
                    textField.setOnAction(e -> commitEdit(textField.getText()));
                }
                textField.setText(getString());
                setText(null);
                setGraphic(textField);
                textField.selectAll();
                textField.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(getString());
                setGraphic(null);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                } else {
                    if (isEditing()) {
                        if (textField != null) textField.setText(getString());
                        setText(null);
                        setGraphic(textField);
                    } else {
                        setText(getString());
                        setGraphic(null);
                    }
                }
            }

            private String getString() {
                return getItem() == null ? "" : getItem();
            }
        };
    }

    /**
     * Fix 5: Enabled 勾选框 Cell 工厂(支持 KeyValueRow 与 FormDataRow)。
     * 勾选状态变化时回调 onRowChange。
     */
    private <S> TableCell<S, Boolean> createEnabledCell(Runnable onRowChange) {
        return new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(item != null && item);
                    checkBox.setOnAction(e -> {
                        int idx = getIndex();
                        if (getTableView() == null || idx < 0 || idx >= getTableView().getItems().size()) {
                            return;
                        }
                        Object row = getTableView().getItems().get(idx);
                        if (row instanceof KeyValueRow kvr) {
                            kvr.setEnabled(checkBox.isSelected());
                        } else if (row instanceof FormDataRow fdr) {
                            fdr.setEnabled(checkBox.isSelected());
                        }
                        if (onRowChange != null) onRowChange.run();
                    });
                    setGraphic(checkBox);
                }
            }
        };
    }

    /** Fix 2: Headers Key 列下拉自动补全 Cell(可编辑 ComboBox) */
    private TableCell<KeyValueRow, String> createHeaderKeyCell() {
        return new TableCell<>() {
            private ComboBox<String> comboBox;

            @Override
            public void startEdit() {
                super.startEdit();
                if (comboBox == null) {
                    comboBox = new ComboBox<>(FXCollections.observableArrayList(COMMON_HEADERS));
                    comboBox.setEditable(true);
                    comboBox.focusedProperty().addListener((obs, o, n) -> {
                        if (!n) {
                            String v = comboBox.getEditor().getText();
                            commitEdit(v);
                        }
                    });
                    comboBox.setOnAction(e -> {
                        String v = comboBox.getValue() != null ? comboBox.getValue() : comboBox.getEditor().getText();
                        commitEdit(v);
                    });
                }
                comboBox.getEditor().setText(getString());
                setText(null);
                setGraphic(comboBox);
                comboBox.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(getString());
                setGraphic(null);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                } else {
                    if (isEditing()) {
                        if (comboBox != null) comboBox.getEditor().setText(getString());
                        setText(null);
                        setGraphic(comboBox);
                    } else {
                        setText(getString());
                        setGraphic(null);
                    }
                }
            }

            private String getString() {
                return getItem() == null ? "" : getItem();
            }
        };
    }

    /** Fix 6: form-data Type 列下拉 Cell(text / file) */
    private TableCell<FormDataRow, String> createFormDataTypeCell() {
        return new TableCell<>() {
            private ComboBox<String> comboBox;

            @Override
            public void startEdit() {
                super.startEdit();
                if (comboBox == null) {
                    comboBox = new ComboBox<>(FXCollections.observableArrayList("text", "file"));
                    comboBox.focusedProperty().addListener((obs, o, n) -> {
                        if (!n) commitEdit(comboBox.getValue());
                    });
                    comboBox.setOnAction(e -> commitEdit(comboBox.getValue()));
                }
                comboBox.setValue(getItem());
                setText(null);
                setGraphic(comboBox);
                comboBox.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(getString());
                setGraphic(null);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                } else {
                    if (isEditing()) {
                        if (comboBox != null) comboBox.setValue(getItem());
                        setText(null);
                        setGraphic(comboBox);
                    } else {
                        setText(getString());
                        setGraphic(null);
                    }
                }
            }

            private String getString() {
                return getItem() == null ? "" : getItem();
            }
        };
    }

    /**
     * Fix 6: form-data Value 列 Cell。
     * <p>
     * type=text 时为可编辑文本框(失焦确认);<br>
     * type=file 时显示文件名,双击打开文件选择器,选中后读取 Base64 存储。
     */
    private TableCell<FormDataRow, String> createFormDataValueCell() {
        return new TableCell<>() {
            private TextField textField;

            @Override
            public void startEdit() {
                FormDataRow row = getTableRow() != null ? getTableRow().getItem() : null;
                if (row != null && "file".equals(row.getType())) {
                    // file 类型: 直接打开文件选择器,不进入文本编辑
                    super.startEdit();
                    chooseFormDataFile(row);
                    cancelEdit();
                    return;
                }
                super.startEdit();
                if (textField == null) {
                    textField = new TextField(getString());
                    textField.focusedProperty().addListener((obs, o, n) -> {
                        if (!n) commitEdit(textField.getText());
                    });
                    textField.setOnAction(e -> commitEdit(textField.getText()));
                }
                textField.setText(getString());
                setText(null);
                setGraphic(textField);
                textField.selectAll();
                textField.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                updateDisplay();
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                updateDisplay();
            }

            private void updateDisplay() {
                if (isEmpty()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                FormDataRow row = getTableRow() != null ? getTableRow().getItem() : null;
                if (row == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (isEditing()) {
                    if ("file".equals(row.getType())) {
                        String fn = row.getFileName();
                        setText((fn == null || fn.isEmpty()) ? "<双击选择文件>" : fn);
                        setGraphic(null);
                    } else {
                        if (textField != null) textField.setText(getString());
                        setText(null);
                        setGraphic(textField);
                    }
                } else if ("file".equals(row.getType())) {
                    String fn = row.getFileName();
                    setText((fn == null || fn.isEmpty()) ? "<双击选择文件>" : fn);
                    setGraphic(null);
                } else {
                    setText(getString());
                    setGraphic(null);
                }
            }

            private String getString() {
                return getItem() == null ? "" : getItem();
            }
        };
    }

    // ==================== 内部数据模型 ====================

    /** Key-Value 行数据(Params / Headers 共用) */
    public static class KeyValueRow {
        private final SimpleStringProperty key = new SimpleStringProperty("");
        private final SimpleStringProperty value = new SimpleStringProperty("");
        private final SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);
        private final SimpleStringProperty description = new SimpleStringProperty("");

        public KeyValueRow(String key, String value, boolean enabled, String description) {
            setKey(key);
            setValue(value);
            setEnabled(enabled);
            setDescription(description);
        }

        public String getKey() { return key.get(); }
        public void setKey(String v) { key.set(v); }
        public SimpleStringProperty keyProperty() { return key; }

        public String getValue() { return value.get(); }
        public void setValue(String v) { value.set(v); }
        public SimpleStringProperty valueProperty() { return value; }

        public boolean isEnabled() { return enabled.get(); }
        public void setEnabled(boolean v) { enabled.set(v); }
        public SimpleBooleanProperty enabledProperty() { return enabled; }

        public String getDescription() { return description.get(); }
        public void setDescription(String v) { description.set(v); }
        public SimpleStringProperty descriptionProperty() { return description; }
    }

    /**
     * form-data 行数据。
     * <p>
     * Fix 6: 新增 fileContent(Base64)与 fileName 字段,文件类型时存储文件内容与文件名。
     */
    public static class FormDataRow {
        private final SimpleStringProperty key = new SimpleStringProperty("");
        private final SimpleStringProperty type = new SimpleStringProperty("text");
        private final SimpleStringProperty value = new SimpleStringProperty("");
        /** file 类型时的文件内容(Base64 编码) */
        private final SimpleStringProperty fileContent = new SimpleStringProperty("");
        /** file 类型时的文件名(不含路径,用于显示) */
        private final SimpleStringProperty fileName = new SimpleStringProperty("");
        private final SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);

        public FormDataRow(String key, String type, String value, boolean enabled) {
            setKey(key);
            setType(type);
            setValue(value);
            setEnabled(enabled);
        }

        public String getKey() { return key.get(); }
        public void setKey(String v) { key.set(v); }
        public SimpleStringProperty keyProperty() { return key; }

        public String getType() { return type.get(); }
        public void setType(String v) { type.set(v); }
        public SimpleStringProperty typeProperty() { return type; }

        public String getValue() { return value.get(); }
        public void setValue(String v) { value.set(v); }
        public SimpleStringProperty valueProperty() { return value; }

        public String getFileContent() { return fileContent.get(); }
        public void setFileContent(String v) { fileContent.set(v); }
        public SimpleStringProperty fileContentProperty() { return fileContent; }

        public String getFileName() { return fileName.get(); }
        public void setFileName(String v) { fileName.set(v); }
        public SimpleStringProperty fileNameProperty() { return fileName; }

        public boolean isEnabled() { return enabled.get(); }
        public void setEnabled(boolean v) { enabled.set(v); }
        public SimpleBooleanProperty enabledProperty() { return enabled; }
    }
}
