package com.jpostman2.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman2.model.component.Header;
import com.jpostman2.model.dto.ResponseData;
import com.jpostman2.model.dto.TimingMetrics;
import com.jpostman2.ui.event.ResponseListener;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 响应展示视图 — 展示 HTTP 响应数据,包括状态码、响应体、响应头、Cookie、时间线。
 * <p>
 * 增强(Win11 版):
 * <ul>
 *   <li>RichTextFX CodeArea 实现 JSON/XML/HTML 语法高亮</li>
 *   <li>新增 Cookies 标签页(解析 Set-Cookie 头)</li>
 *   <li>新增图片预览视图(二进制响应)</li>
 *   <li>响应头支持表格/文本模式切换</li>
 *   <li>右键复制(行/全部) + 列排序</li>
 * </ul>
 */
@Lazy
@Component
public class ResponseView implements ResponseListener {

    private static final Logger log = LoggerFactory.getLogger(ResponseView.class);

    /** JSON 高亮正则: 键名 / 字符串 / 数字 / 布尔+null */
    private static final Pattern JSON_PATTERN = Pattern.compile(
            "(?<KEY>\"[^\"\\n]*\"\\s*:)|" +
            "(?<STRING>\"[^\"\\n]*\")|" +
            "(?<NUMBER>-?\\b\\d+\\.?\\d*([eE][+-]?\\d+)?\\b)|" +
            "(?<BOOL>\\b(true|false|null)\\b)"
    );

    /** XML/HTML 高亮正则: 标签名 / 属性名 / 属性值 / 注释 */
    private static final Pattern XML_PATTERN = Pattern.compile(
            "(?<TAG></?[a-zA-Z_][\\w:-]*>?)|" +
            "(?<ATTRNAME>\\s[a-zA-Z_][\\w:-]*=)|" +
            "(?<ATTRVALUE>\"[^\"]*\")|" +
            "(?<COMMENT>&lt;!--.*?--&gt;|<!--.*?-->)"
    );

    private final ObjectMapper objectMapper;

    private VBox root;
    private Label statusCodeLabel;
    private Label statusTextLabel;
    private Label timeLabel;
    private Label sizeLabel;
    private Label ttfbLabel;

    // Body 标签页
    private StackPane bodyStack;
    private ToggleGroup bodyViewToggle;
    private CodeArea prettyBodyArea;
    private TextArea rawBodyArea;
    private WebView previewWebView;
    private ImageView imageView;
    private ScrollPane imageScrollPane;
    private ToggleButton imageBtn;

    // Headers 标签页
    private StackPane headersStack;
    private TableView<HeaderRow> headersTable;
    private final ObservableList<HeaderRow> headersData = FXCollections.observableArrayList();
    private TextArea headersTextMode;
    private boolean textMode = false;

    // Cookies 标签页
    private TableView<CookieRow> cookiesTable;
    private final ObservableList<CookieRow> cookiesData = FXCollections.observableArrayList();

    // Timeline 标签页
    private VBox timelineContainer;

    public ResponseView(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        buildView();
    }

    public VBox getRoot() {
        return root;
    }

    @Override
    public void onResponse(ResponseData response) {
        Platform.runLater(() -> updateResponse(response));
    }

    // ==================== UI 构建 ====================

    private void buildView() {
        root = new VBox(0);
        root.getStyleClass().add("response-view");

        root.getChildren().add(buildInfoBar());

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("response-tabs");

        Tab bodyTab = new Tab("响应体");
        bodyTab.setContent(buildBodyTab());

        Tab headersTab = new Tab("响应头");
        headersTab.setContent(buildHeadersTab());

        Tab cookiesTab = new Tab("Cookie");
        cookiesTab.setContent(buildCookiesTab());

        Tab timelineTab = new Tab("时间线");
        timelineTab.setContent(buildTimelineTab());

        tabPane.getTabs().addAll(bodyTab, headersTab, cookiesTab, timelineTab);

        VBox.setVgrow(tabPane, Priority.ALWAYS);
        root.getChildren().add(tabPane);
    }

    private HBox buildInfoBar() {
        HBox bar = new HBox(16);
        bar.getStyleClass().add("response-info-bar");
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.CENTER_LEFT);

        statusCodeLabel = new Label("--");
        statusCodeLabel.getStyleClass().add("status-code-label");

        statusTextLabel = new Label("");
        statusTextLabel.getStyleClass().add("status-text-label");

        timeLabel = new Label("耗时: --");
        timeLabel.getStyleClass().add("metric-label");

        sizeLabel = new Label("大小: --");
        sizeLabel.getStyleClass().add("metric-label");

        ttfbLabel = new Label("首字节: --");
        ttfbLabel.getStyleClass().add("metric-label");

        bar.getChildren().addAll(statusCodeLabel, statusTextLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                timeLabel, sizeLabel, ttfbLabel);
        return bar;
    }

    // ==================== Body 标签页 ====================

    private VBox buildBodyTab() {
        VBox container = new VBox(4);
        container.setPadding(new Insets(8));

        HBox toggleBar = new HBox(4);
        bodyViewToggle = new ToggleGroup();

        ToggleButton prettyBtn = new ToggleButton("格式化");
        prettyBtn.setToggleGroup(bodyViewToggle);
        prettyBtn.setSelected(true);
        prettyBtn.setOnAction(e -> showBodyView("pretty"));

        ToggleButton rawBtn = new ToggleButton("原始");
        rawBtn.setToggleGroup(bodyViewToggle);
        rawBtn.setOnAction(e -> showBodyView("raw"));

        ToggleButton previewBtn = new ToggleButton("预览");
        previewBtn.setToggleGroup(bodyViewToggle);
        previewBtn.setOnAction(e -> showBodyView("preview"));

        imageBtn = new ToggleButton("图片");
        imageBtn.setToggleGroup(bodyViewToggle);
        imageBtn.setDisable(true);
        imageBtn.setOnAction(e -> showBodyView("image"));

        toggleBar.getChildren().addAll(prettyBtn, rawBtn, previewBtn, imageBtn);

        bodyStack = new StackPane();

        // Pretty 视图 (RichTextFX CodeArea)
        prettyBodyArea = new CodeArea();
        prettyBodyArea.setEditable(false);
        prettyBodyArea.setWrapText(true);
        prettyBodyArea.getStyleClass().add("code-area");
        prettyBodyArea.setStyle("-fx-background-color: #252526; -fx-text-fill: #d4d4d4;");

        // Raw 视图
        rawBodyArea = new TextArea();
        rawBodyArea.setEditable(false);
        rawBodyArea.setWrapText(true);
        rawBodyArea.getStyleClass().add("response-body-area");

        // Preview 视图 (WebView)
        previewWebView = new WebView();
        previewWebView.setPrefHeight(400);

        // Image 视图
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(600);
        imageScrollPane = new ScrollPane(imageView);
        imageScrollPane.setFitToWidth(true);
        imageScrollPane.setFitToHeight(true);

        showBodyView("pretty");

        container.getChildren().addAll(toggleBar, bodyStack);
        VBox.setVgrow(bodyStack, Priority.ALWAYS);
        return container;
    }

    private void showBodyView(String view) {
        bodyStack.getChildren().clear();
        switch (view) {
            case "raw" -> bodyStack.getChildren().add(rawBodyArea);
            case "preview" -> bodyStack.getChildren().add(previewWebView);
            case "image" -> bodyStack.getChildren().add(imageScrollPane);
            default -> bodyStack.getChildren().add(prettyBodyArea);
        }
    }

    // ==================== Headers 标签页 ====================

    private VBox buildHeadersTab() {
        VBox container = new VBox(4);
        container.setPadding(new Insets(8));

        // 工具栏: 文本模式切换
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        ToggleButton textModeToggle = new ToggleButton("文本模式");
        textModeToggle.setSelected(false);
        textModeToggle.selectedProperty().addListener((obs, o, n) -> {
            textMode = n;
            showHeadersMode();
        });
        toolbar.getChildren().add(textModeToggle);

        // 表格视图
        headersTable = new TableView<>();
        headersTable.setPlaceholder(new Label("暂无响应头"));

        TableColumn<HeaderRow, String> keyCol = new TableColumn<>("头部名");
        keyCol.setCellValueFactory(p -> p.getValue().keyProperty());
        keyCol.setPrefWidth(250);
        keyCol.setSortable(true);
        keyCol.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));

        TableColumn<HeaderRow, String> valueCol = new TableColumn<>("值");
        valueCol.setCellValueFactory(p -> p.getValue().valueProperty());
        valueCol.setPrefWidth(500);
        valueCol.setSortable(true);
        valueCol.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));

        headersTable.getColumns().addAll(keyCol, valueCol);
        headersTable.setItems(headersData);

        // 右键复制
        attachHeaderCopyMenu();

        // 文本模式视图
        headersTextMode = new TextArea();
        headersTextMode.setEditable(false);
        headersTextMode.setWrapText(false);
        headersTextMode.getStyleClass().add("response-body-area");
        headersTextMode.setVisible(false);
        headersTextMode.setManaged(false);

        headersStack = new StackPane();
        headersStack.getChildren().addAll(headersTable, headersTextMode);

        VBox.setVgrow(headersStack, Priority.ALWAYS);
        container.getChildren().addAll(toolbar, headersStack);
        return container;
    }

    private void showHeadersMode() {
        headersTable.setVisible(!textMode);
        headersTable.setManaged(!textMode);
        headersTextMode.setVisible(textMode);
        headersTextMode.setManaged(textMode);
    }

    private void attachHeaderCopyMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem copyRow = new MenuItem("复制行");
        MenuItem copyAll = new MenuItem("复制全部");

        copyRow.setOnAction(e -> {
            HeaderRow selected = headersTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                copyToClipboard(selected.getKey() + ": " + selected.getValue());
            }
        });

        copyAll.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (HeaderRow row : headersData) {
                sb.append(row.getKey()).append(": ").append(row.getValue()).append("\n");
            }
            copyToClipboard(sb.toString());
        });

        menu.getItems().addAll(copyRow, copyAll);
        headersTable.setContextMenu(menu);
    }

    // ==================== Cookies 标签页 ====================

    private VBox buildCookiesTab() {
        VBox container = new VBox(4);
        container.setPadding(new Insets(8));

        cookiesTable = new TableView<>();
        cookiesTable.setPlaceholder(new Label("暂无 Cookie (发送请求后自动解析 Set-Cookie)"));

        TableColumn<CookieRow, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(p -> p.getValue().nameProperty());
        nameCol.setPrefWidth(150);
        nameCol.setSortable(true);

        TableColumn<CookieRow, String> valueCol = new TableColumn<>("值");
        valueCol.setCellValueFactory(p -> p.getValue().valueProperty());
        valueCol.setPrefWidth(200);
        valueCol.setSortable(true);

        TableColumn<CookieRow, String> domainCol = new TableColumn<>("域");
        domainCol.setCellValueFactory(p -> p.getValue().domainProperty());
        domainCol.setPrefWidth(150);
        domainCol.setSortable(true);

        TableColumn<CookieRow, String> pathCol = new TableColumn<>("路径");
        pathCol.setCellValueFactory(p -> p.getValue().pathProperty());
        pathCol.setPrefWidth(100);

        TableColumn<CookieRow, Boolean> secureCol = new TableColumn<>("安全");
        secureCol.setCellValueFactory(p -> p.getValue().secureProperty());
        secureCol.setPrefWidth(60);
        secureCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : (item ? "✓" : ""));
            }
        });

        TableColumn<CookieRow, Boolean> httpOnlyCol = new TableColumn<>("仅HTTP");
        httpOnlyCol.setCellValueFactory(p -> p.getValue().httpOnlyProperty());
        httpOnlyCol.setPrefWidth(70);
        httpOnlyCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : (item ? "✓" : ""));
            }
        });

        cookiesTable.getColumns().addAll(nameCol, valueCol, domainCol, pathCol, secureCol, httpOnlyCol);
        cookiesTable.setItems(cookiesData);

        // 右键复制
        ContextMenu cookieMenu = new ContextMenu();
        MenuItem copyCookie = new MenuItem("复制行");
        MenuItem copyAllCookies = new MenuItem("复制全部");
        copyCookie.setOnAction(e -> {
            CookieRow selected = cookiesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                copyToClipboard(selected.getName() + "=" + selected.getValue());
            }
        });
        copyAllCookies.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (CookieRow row : cookiesData) {
                sb.append(row.getName()).append("=").append(row.getValue()).append("\n");
            }
            copyToClipboard(sb.toString());
        });
        cookieMenu.getItems().addAll(copyCookie, copyAllCookies);
        cookiesTable.setContextMenu(cookieMenu);

        VBox.setVgrow(cookiesTable, Priority.ALWAYS);
        container.getChildren().add(cookiesTable);
        return container;
    }

    // ==================== Timeline 标签页 ====================

    private VBox buildTimelineTab() {
        timelineContainer = new VBox(8);
        timelineContainer.setPadding(new Insets(16));

        Label hint = new Label("发送请求后显示时间线");
        hint.setOpacity(0.5);
        timelineContainer.getChildren().add(hint);
        return timelineContainer;
    }

    // ==================== 响应更新 ====================

    private void updateResponse(ResponseData response) {
        // 状态码 + 颜色
        int code = response.getStatusCode();
        statusCodeLabel.setText(String.valueOf(code));
        statusCodeLabel.setStyle(getStatusCodeColor(code));

        // 状态文本
        String statusText = response.getStatusText();
        if (response.getError() != null) {
            statusTextLabel.setText(response.getError());
        } else {
            statusTextLabel.setText(statusText != null ? statusText : "");
        }

        // 性能指标
        TimingMetrics timing = response.getTiming();
        if (timing != null) {
            timeLabel.setText(String.format("耗时: %dms", timing.getTotalMs()));
            ttfbLabel.setText(String.format("首字节: %dms", timing.getTtfbMs()));
        } else {
            timeLabel.setText("耗时: --");
            ttfbLabel.setText("首字节: --");
        }

        sizeLabel.setText(formatSize(response.getSize()));

        // 响应体
        String body = response.getBody();
        if (body == null) body = "";

        rawBodyArea.setText(body);

        // Pretty (格式化 + 高亮)
        String formatted = formatBody(body, response.getContentType());
        updatePrettyView(formatted, response.getContentType());

        // Preview (HTML 渲染)
        String contentType = response.getContentType();
        if (contentType != null && contentType.contains("text/html")) {
            previewWebView.getEngine().loadContent(body);
        } else {
            previewWebView.getEngine().loadContent(
                    "<html><body><pre style='white-space:pre-wrap;'>" +
                            escapeHtml(body) + "</pre></body></html>");
        }

        // 图片预览
        if (response.getBinaryBase64() != null) {
            try {
                byte[] bytes = Base64.getDecoder().decode(response.getBinaryBase64());
                Image image = new Image(new ByteArrayInputStream(bytes));
                imageView.setImage(image);
                imageBtn.setDisable(false);
            } catch (Exception e) {
                log.warn("图片预览加载失败", e);
                imageBtn.setDisable(true);
                imageView.setImage(null);
            }
        } else {
            imageBtn.setDisable(true);
            imageView.setImage(null);
        }

        // 响应头
        headersData.clear();
        StringBuilder headerText = new StringBuilder();
        for (Header header : response.getHeaders()) {
            headersData.add(new HeaderRow(header.getKey(), header.getValue()));
            headerText.append(header.getKey()).append(": ").append(header.getValue()).append("\n");
        }
        headersTextMode.setText(headerText.toString());

        // Cookie
        updateCookies(response.getHeaders());

        // 时间线
        updateTimeline(timing);
    }

    private void updatePrettyView(String text, String contentType) {
        prettyBodyArea.replaceText(0, prettyBodyArea.getLength(), text);
        // 对小于 100KB 的文本应用语法高亮
        if (text.length() > 0 && text.length() <= 100_000) {
            try {
                StyleSpans<Collection<String>> spans = computeHighlighting(text, contentType);
                prettyBodyArea.setStyleSpans(0, spans);
            } catch (Exception e) {
                log.debug("语法高亮失败, 使用纯文本", e);
            }
        }
    }

    private void updateCookies(List<Header> headers) {
        cookiesData.clear();
        if (headers == null) return;
        for (Header header : headers) {
            if (header.getKey() == null) continue;
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
            String value = header.getValue();
            if (value == null) continue;
            // 解析: name=value; Domain=...; Path=...; Secure; HttpOnly
            String[] parts = value.split(";");
            if (parts.length == 0) continue;
            String[] nv = parts[0].split("=", 2);
            if (nv.length < 2) continue;
            String name = nv[0].trim();
            String cookieValue = nv[1].trim();
            String domain = "";
            String path = "/";
            boolean secure = false;
            boolean httpOnly = false;
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                String lower = part.toLowerCase(Locale.ROOT);
                if (lower.startsWith("domain=")) {
                    domain = part.substring(7);
                } else if (lower.startsWith("path=")) {
                    path = part.substring(5);
                } else if (lower.equals("secure")) {
                    secure = true;
                } else if (lower.equals("httponly")) {
                    httpOnly = true;
                }
            }
            cookiesData.add(new CookieRow(name, cookieValue, domain, path, secure, httpOnly));
        }
    }

    private void updateTimeline(TimingMetrics timing) {
        timelineContainer.getChildren().clear();
        if (timing == null) {
            Label hint = new Label("无性能指标数据");
            hint.setOpacity(0.5);
            timelineContainer.getChildren().add(hint);
            return;
        }

        Label title = new Label("请求时间线");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        long total = timing.getTotalMs();
        if (total <= 0) total = 1;

        timelineContainer.getChildren().addAll(title,
                createTimelineRow("DNS 解析", timing.getDnsMs(), total),
                createTimelineRow("TCP 连接", timing.getTcpMs(), total),
                createTimelineRow("TLS 握手", timing.getTlsMs(), total),
                createTimelineRow("首字节时间 (TTFB)", timing.getTtfbMs(), total),
                new Separator(),
                createTimelineRow("总耗时", timing.getTotalMs(), timing.getTotalMs()));
    }

    private HBox createTimelineRow(String label, long ms, long total) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(label);
        nameLabel.setPrefWidth(150);
        Label timeLabel = new Label(ms + " ms");
        timeLabel.setPrefWidth(80);
        timeLabel.setStyle("-fx-font-family: monospace;");
        ProgressBar progressBar = new ProgressBar(total > 0 ? (double) ms / total : 0);
        progressBar.setPrefWidth(200);
        progressBar.setMaxWidth(200);
        row.getChildren().addAll(nameLabel, timeLabel, progressBar);
        return row;
    }

    // ==================== 语法高亮 ====================

    private StyleSpans<Collection<String>> computeHighlighting(String text, String contentType) {
        if (contentType != null && contentType.contains("json")) {
            return computeJsonHighlighting(text);
        } else if (contentType != null && (contentType.contains("xml") || contentType.contains("html"))) {
            return computeXmlHighlighting(text);
        } else if (looksLikeJson(text)) {
            return computeJsonHighlighting(text);
        }
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        builder.add(Collections.emptyList(), text.length());
        return builder.create();
    }

    private StyleSpans<Collection<String>> computeJsonHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        Matcher matcher = JSON_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String styleClass;
            if (matcher.group("KEY") != null) {
                styleClass = "json-key";
            } else if (matcher.group("STRING") != null) {
                styleClass = "json-string";
            } else if (matcher.group("NUMBER") != null) {
                styleClass = "json-number";
            } else if (matcher.group("BOOL") != null) {
                styleClass = "json-boolean";
            } else {
                continue;
            }
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    private StyleSpans<Collection<String>> computeXmlHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        Matcher matcher = XML_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String styleClass;
            if (matcher.group("TAG") != null) {
                styleClass = "xml-tag";
            } else if (matcher.group("ATTRNAME") != null) {
                styleClass = "xml-attr-name";
            } else if (matcher.group("ATTRVALUE") != null) {
                styleClass = "xml-attr-value";
            } else if (matcher.group("COMMENT") != null) {
                styleClass = "xml-comment";
            } else {
                continue;
            }
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    // ==================== 工具方法 ====================

    private String getStatusCodeColor(int code) {
        if (code == 0) return "-fx-text-fill: #9e9e9e; -fx-font-weight: bold; -fx-font-size: 16px;";
        if (code < 300) return "-fx-text-fill: #4caf50; -fx-font-weight: bold; -fx-font-size: 16px;";
        if (code < 400) return "-fx-text-fill: #2196f3; -fx-font-weight: bold; -fx-font-size: 16px;";
        if (code < 500) return "-fx-text-fill: #ff9800; -fx-font-weight: bold; -fx-font-size: 16px;";
        return "-fx-text-fill: #f44336; -fx-font-weight: bold; -fx-font-size: 16px;";
    }

    private String formatBody(String body, String contentType) {
        if (body == null || body.isBlank()) return "";
        if (contentType != null && contentType.contains("json") || looksLikeJson(body)) {
            try {
                JsonNode node = objectMapper.readTree(body);
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            } catch (Exception ignored) {
            }
        }
        return body;
    }

    private boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return "大小: " + bytes + " B";
        if (bytes < 1024 * 1024) return String.format("大小: %.1f KB", bytes / 1024.0);
        return String.format("大小: %.1f MB", bytes / (1024.0 * 1024));
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ==================== 内部数据模型 ====================

    public static class HeaderRow {
        private final javafx.beans.property.SimpleStringProperty key = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty value = new javafx.beans.property.SimpleStringProperty("");

        public HeaderRow(String key, String value) {
            setKey(key);
            setValue(value);
        }

        public String getKey() { return key.get(); }
        public void setKey(String v) { key.set(v); }
        public javafx.beans.property.SimpleStringProperty keyProperty() { return key; }

        public String getValue() { return value.get(); }
        public void setValue(String v) { value.set(v); }
        public javafx.beans.property.SimpleStringProperty valueProperty() { return value; }
    }

    public static class CookieRow {
        private final javafx.beans.property.SimpleStringProperty name = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty value = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty domain = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty path = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleBooleanProperty secure = new javafx.beans.property.SimpleBooleanProperty(false);
        private final javafx.beans.property.SimpleBooleanProperty httpOnly = new javafx.beans.property.SimpleBooleanProperty(false);

        public CookieRow(String name, String value, String domain, String path, boolean secure, boolean httpOnly) {
            setName(name);
            setValue(value);
            setDomain(domain);
            setPath(path);
            setSecure(secure);
            setHttpOnly(httpOnly);
        }

        public String getName() { return name.get(); }
        public void setName(String v) { name.set(v); }
        public javafx.beans.property.SimpleStringProperty nameProperty() { return name; }

        public String getValue() { return value.get(); }
        public void setValue(String v) { value.set(v); }
        public javafx.beans.property.SimpleStringProperty valueProperty() { return value; }

        public String getDomain() { return domain.get(); }
        public void setDomain(String v) { domain.set(v); }
        public javafx.beans.property.SimpleStringProperty domainProperty() { return domain; }

        public String getPath() { return path.get(); }
        public void setPath(String v) { path.set(v); }
        public javafx.beans.property.SimpleStringProperty pathProperty() { return path; }

        public boolean isSecure() { return secure.get(); }
        public void setSecure(boolean v) { secure.set(v); }
        public javafx.beans.property.SimpleBooleanProperty secureProperty() { return secure; }

        public boolean isHttpOnly() { return httpOnly.get(); }
        public void setHttpOnly(boolean v) { httpOnly.set(v); }
        public javafx.beans.property.SimpleBooleanProperty httpOnlyProperty() { return httpOnly; }
    }
}
