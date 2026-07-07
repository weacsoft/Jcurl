package com.jcurl.ui;

import com.jcurl.model.HistoryRecord;
import com.jcurl.service.HistoryService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * 历史记录视图 — 左侧栏组件,展示 API 请求历史。
 * <p>
 * 功能:
 * <ul>
 *   <li>ListView 展示历史记录,每行格式: {@code [Method] URL (时间) - StatusCode}</li>
 *   <li>HTTP 方法颜色区分: GET 绿 / POST 黄 / PUT 蓝 / DELETE 红 / PATCH 紫</li>
 *   <li>搜索框: 按 URL / Method / 名称关键词搜索历史</li>
 *   <li>清空历史按钮(带确认对话框)</li>
 *   <li>双击历史记录触发 {@link #selectionCallback},通知请求构建器重新加载该请求</li>
 *   <li>右键菜单: 重新加载此请求 / 删除此记录</li>
 *   <li>{@link #refresh()} 从 {@link HistoryService} 加载数据并刷新列表</li>
 * </ul>
 * <p>
 * 使用 @Lazy 注解:Spring 容器启动时不立即实例化(此时 JavaFX Toolkit 未初始化),
 * 仅在主界面构建时按需创建。数据来源 {@link HistoryService},存储于 history.json。
 *
 * @see HistoryService
 * @see HistoryRecord
 */
@Lazy
@Component
public class HistoryView {

    private static final Logger log = LoggerFactory.getLogger(HistoryView.class);

    /** 时间格式化器 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HistoryService historyService;

    /** 历史记录选中回调(由请求构建器设置,双击时通知重新加载该请求) */
    private Consumer<HistoryRecord> selectionCallback;

    // ==================== UI 组件 ====================

    private VBox root;
    private TextField searchField;
    private Button searchButton;
    private Button clearButton;
    private ListView<HistoryRecord> historyList;
    private final ObservableList<HistoryRecord> historyData = FXCollections.observableArrayList();

    public HistoryView(HistoryService historyService) {
        this.historyService = historyService;
        buildView();
    }

    /** 返回根布局 */
    public VBox getRoot() {
        return root;
    }

    /** 设置历史记录选中回调(双击历史记录时触发) */
    public void setSelectionCallback(Consumer<HistoryRecord> callback) {
        this.selectionCallback = callback;
    }

    // ==================== UI 构建 ====================

    private void buildView() {
        root = new VBox(4);
        root.getStyleClass().add("history-view");

        // 顶部: 搜索框 + 搜索按钮 + 清空历史按钮
        root.getChildren().add(buildSearchBar());

        // 中间: 历史记录列表
        historyList = new ListView<>(historyData);
        historyList.getStyleClass().add("history-list");
        historyList.setCellFactory(lv -> new HistoryListCell());
        historyList.setPlaceholder(new Label("暂无历史记录"));
        historyList.setContextMenu(buildListContextMenu());

        // 双击历史记录触发选中回调
        historyList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                HistoryRecord selected = historyList.getSelectionModel().getSelectedItem();
                if (selected != null && selectionCallback != null) {
                    selectionCallback.accept(selected);
                }
            }
        });

        VBox.setVgrow(historyList, Priority.ALWAYS);
        root.getChildren().add(historyList);
    }

    /** 顶部搜索栏: 搜索框 + 搜索按钮 + 清空历史按钮 */
    private HBox buildSearchBar() {
        HBox bar = new HBox(6);
        bar.getStyleClass().add("history-search");
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("搜索 URL / 方法 / 名称");
        searchField.getStyleClass().add("history-search-field");
        // 回车触发搜索
        searchField.setOnAction(e -> doSearch());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchButton = new Button("搜索");
        searchButton.getStyleClass().add("history-search-button");
        searchButton.setOnAction(e -> doSearch());

        clearButton = new Button("清空历史");
        clearButton.getStyleClass().add("history-clear-button");
        clearButton.setOnAction(e -> confirmClearHistory());

        bar.getChildren().addAll(searchField, searchButton, clearButton);
        return bar;
    }

    /** 列表右键菜单: 重新加载此请求 / 删除此记录 */
    private ContextMenu buildListContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem reload = new MenuItem("重新加载此请求");
        reload.setOnAction(e -> {
            HistoryRecord selected = historyList.getSelectionModel().getSelectedItem();
            if (selected != null && selectionCallback != null) {
                selectionCallback.accept(selected);
            }
        });

        MenuItem delete = new MenuItem("删除此记录");
        delete.setStyle("-fx-text-fill: #f44336;");
        delete.setOnAction(e -> {
            HistoryRecord selected = historyList.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getId() != null) {
                try {
                    historyService.deleteHistory(selected.getId());
                    refresh();
                } catch (Exception ex) {
                    log.error("删除历史记录失败: id={}", selected.getId(), ex);
                }
            }
        });

        menu.getItems().addAll(reload, new SeparatorMenuItem(), delete);
        return menu;
    }

    // ==================== 数据加载 ====================

    /**
     * 从 HistoryService 加载数据并刷新列表。
     * <p>
     * 若搜索框有内容则按关键词搜索(匹配 URL / Method / 名称),
     * 否则加载全部历史(按时间倒序,最新的在前)。
     */
    public void refresh() {
        try {
            String keyword = searchField.getText();
            List<HistoryRecord> records;
            if (keyword != null && !keyword.isBlank()) {
                records = historyService.searchHistory(keyword.trim());
            } else {
                records = historyService.listHistory();
            }
            historyData.setAll(records);
            log.debug("刷新历史记录: count={}", records.size());
        } catch (Exception e) {
            log.error("加载历史记录失败", e);
            historyData.clear();
        }
    }

    /** 执行搜索 */
    private void doSearch() {
        refresh();
    }

    /** 确认清空所有历史记录 */
    private void confirmClearHistory() {
        if (historyData.isEmpty()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("清空历史记录");
        alert.setHeaderText(null);
        alert.setContentText("确定要清空所有历史记录吗?此操作不可撤销。");
        alert.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                try {
                    historyService.clearHistory();
                    refresh();
                    log.info("已清空所有历史记录");
                } catch (Exception e) {
                    log.error("清空历史记录失败", e);
                }
            }
        });
    }

    // ==================== 工具方法 ====================

    /** HTTP 方法颜色: GET 绿 / POST 黄 / PUT 蓝 / DELETE 红 / PATCH 紫 */
    private String getMethodColor(String method) {
        if (method == null) return "#9e9e9e";
        return switch (method.toUpperCase()) {
            case "GET" -> "#4caf50";      // 绿色
            case "POST" -> "#ffc107";     // 黄色
            case "PUT" -> "#2196f3";      // 蓝色
            case "DELETE" -> "#f44336";   // 红色
            case "PATCH" -> "#9c27b0";    // 紫色
            default -> "#9e9e9e";         // 灰色
        };
    }

    /** 状态码颜色: 2xx 绿 / 3xx 蓝 / 4xx 橙 / 5xx 红 / 0 灰 */
    private String getStatusCodeColor(int code) {
        if (code == 0) return "#9e9e9e";
        if (code < 300) return "#4caf50";
        if (code < 400) return "#2196f3";
        if (code < 500) return "#ff9800";
        return "#f44336";
    }

    // ==================== 自定义单元格 ====================

    /**
     * 历史记录单元格 — 每行显示 {@code [Method] URL (时间) - StatusCode},
     * Method 用颜色区分。
     */
    private class HistoryListCell extends ListCell<HistoryRecord> {

        @Override
        protected void updateItem(HistoryRecord item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            String method = item.getMethod() != null ? item.getMethod() : "?";

            // [Method] — 方法颜色 + 加粗
            Text methodText = new Text("[" + method + "] ");
            methodText.setFill(Color.web(getMethodColor(method)));
            methodText.setStyle("-fx-font-weight: bold;");

            // URL
            Text urlText = new Text(item.getUrl() != null ? item.getUrl() : "");
            urlText.setFill(Color.web("#d4d4d4"));

            // (时间)
            String time = item.getTimestamp() != null
                    ? item.getTimestamp().format(TIME_FMT) : "";
            Text timeText = new Text(" (" + time + ")");
            timeText.setFill(Color.web("#888888"));

            // - StatusCode StatusText
            int code = item.getStatusCode();
            String status = code > 0
                    ? (code + (item.getStatusText() != null && !item.getStatusText().isBlank()
                            ? " " + item.getStatusText() : ""))
                    : "无响应";
            Text statusText = new Text(" - " + status);
            statusText.setFill(Color.web(getStatusCodeColor(code)));

            TextFlow flow = new TextFlow(methodText, urlText, timeText, statusText);
            flow.getStyleClass().add("history-list-cell");
            setGraphic(flow);
            setText(null);
        }
    }
}
