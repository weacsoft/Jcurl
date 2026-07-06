package com.jpostman2.ui;

import com.jpostman2.service.CookieService;
import com.jpostman2.service.CookieService.CookieEntry;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Cookie 管理对话框 — 以表格展示当前集合 (Collection) 的所有 Cookie, 支持新增 / 删除 / 清空 / 刷新。
 * <p>
 * 数据来源: {@link CookieService#getAllCookiesFlat()} 返回当前集合上下文的扁平 Cookie 列表。
 * 所有写操作通过 {@link CookieService} 落库 (按集合持久化), 操作完成后刷新表格。
 * <p>
 * 布局:
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │ 当前集合: xxx | 共 N 条 Cookie                          │ ← 状态栏
 * ├──────────────────────────────────────────────────────┤
 * │ [添加 Cookie][删除选中][清空全部][刷新]                  │ ← 工具栏
 * ├──────────────────────────────────────────────────────┤
 * │ Name │ Value │ Domain │ Path │ Expiry │ Secure │ HttpOnly │
 * │  ... │  ...  │  ...   │ ...  │  ...   │  ☐    │  ☐      │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 暗色主题样式: 根容器使用 CSS class {@code cookie-dialog}, 表格使用 {@code env-table},
 * 按钮使用 {@code toolbar-button}。
 */
@Lazy
@Component
public class CookieManagerDialog {

    private static final Logger log = LoggerFactory.getLogger(CookieManagerDialog.class);

    private final CookieService cookieService;

    public CookieManagerDialog(CookieService cookieService) {
        this.cookieService = cookieService;
    }

    /**
     * 显示 Cookie 管理对话框。
     *
     * @param owner 父窗口 (可为 null)
     */
    public void showDialog(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Cookie 管理");
        dialog.initOwner(owner);
        dialog.setHeaderText("当前集合: " + currentCollectionLabel());

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("cookie-dialog");
        applyDarkStylesheet(pane);
        pane.getButtonTypes().add(ButtonType.CLOSE);

        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        content.getStyleClass().add("cookie-dialog");

        // 状态标签
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("sidebar-title");

        // Cookie 表格
        TableView<CookieEntry> table = createCookieTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        ObservableList<CookieEntry> data = FXCollections.observableArrayList();
        table.setItems(data);

        // 工具栏按钮
        Button addBtn = new Button("添加 Cookie");
        Button deleteBtn = new Button("删除选中");
        Button clearBtn = new Button("清空全部");
        Button refreshBtn = new Button("刷新");
        addBtn.getStyleClass().add("toolbar-button");
        deleteBtn.getStyleClass().add("toolbar-button");
        clearBtn.getStyleClass().add("toolbar-button");
        refreshBtn.getStyleClass().add("toolbar-button");

        addBtn.setOnAction(e -> {
            Optional<CookieEntry> result = showAddForm(owner);
            result.ifPresent(entry -> {
                cookieService.addCookie(entry);
                log.info("添加 Cookie: {} @ {}", entry.getName(), entry.getDomain());
                refreshTable(data, statusLabel);
            });
        });

        deleteBtn.setOnAction(e -> {
            CookieEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showWarning(owner, "请先选择要删除的 Cookie");
                return;
            }
            cookieService.deleteCookie(selected.getDomain(), selected.getName());
            log.info("删除 Cookie: {} @ {}", selected.getName(), selected.getDomain());
            refreshTable(data, statusLabel);
        });

        clearBtn.setOnAction(e -> {
            if (data.isEmpty()) {
                showInfo(owner, "当前集合没有 Cookie");
                return;
            }
            if (confirm(owner, "确定要清空当前集合的所有 Cookie 吗?")) {
                cookieService.clearAll();
                log.info("清空当前集合 Cookie");
                refreshTable(data, statusLabel);
            }
        });

        refreshBtn.setOnAction(e -> refreshTable(data, statusLabel));

        ToolBar toolbar = new ToolBar(addBtn, deleteBtn, clearBtn, refreshBtn);
        toolbar.getStyleClass().add("env-toolbar");

        content.getChildren().addAll(statusLabel, toolbar, table);
        pane.setContent(content);

        refreshTable(data, statusLabel);
        dialog.showAndWait();
    }

    // ==================== UI 构建 ====================

    /**
     * 创建 Cookie 表格 (Name / Value / Domain / Path / Expiry / Secure / HttpOnly)。
     */
    private TableView<CookieEntry> createCookieTable() {
        TableView<CookieEntry> table = new TableView<>();
        table.getStyleClass().add("env-table");
        table.setPlaceholder(new Label("当前集合暂无 Cookie"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<CookieEntry, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(140);

        TableColumn<CookieEntry, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(200);

        TableColumn<CookieEntry, String> domainCol = new TableColumn<>("Domain");
        domainCol.setCellValueFactory(new PropertyValueFactory<>("domain"));
        domainCol.setPrefWidth(160);

        TableColumn<CookieEntry, String> pathCol = new TableColumn<>("Path");
        pathCol.setCellValueFactory(new PropertyValueFactory<>("path"));
        pathCol.setPrefWidth(100);

        // Expiry 列: 格式化时间显示 (0 表示 Session Cookie)
        TableColumn<CookieEntry, Number> expiryCol = new TableColumn<>("Expiry");
        expiryCol.setCellValueFactory(new PropertyValueFactory<>("expiry"));
        expiryCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                } else {
                    CookieEntry row = getTableRow() != null ? getTableRow().getItem() : null;
                    setText(formatExpiry(row));
                }
            }
        });
        expiryCol.setPrefWidth(170);

        TableColumn<CookieEntry, Boolean> secureCol = new TableColumn<>("Secure");
        secureCol.setCellValueFactory(new PropertyValueFactory<>("secure"));
        secureCol.setCellFactory(col -> new ReadOnlyCheckBoxCell());
        secureCol.setPrefWidth(70);

        TableColumn<CookieEntry, Boolean> httpOnlyCol = new TableColumn<>("HttpOnly");
        httpOnlyCol.setCellValueFactory(new PropertyValueFactory<>("httpOnly"));
        httpOnlyCol.setCellFactory(col -> new ReadOnlyCheckBoxCell());
        httpOnlyCol.setPrefWidth(80);

        table.getColumns().addAll(nameCol, valueCol, domainCol, pathCol, expiryCol, secureCol, httpOnlyCol);
        return table;
    }

    /**
     * 显示 "添加 Cookie" 表单对话框。返回用户填写的 {@link CookieEntry}, 取消则返回 empty。
     */
    private Optional<CookieEntry> showAddForm(Window owner) {
        Dialog<CookieEntry> dialog = new Dialog<>();
        dialog.setTitle("添加 Cookie");
        dialog.initOwner(owner);
        dialog.setHeaderText("为当前集合新增一条 Cookie");

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("cookie-dialog");
        applyDarkStylesheet(pane);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("如 sessionId");
        TextField valueField = new TextField();
        valueField.setPromptText("如 abc123");
        TextField domainField = new TextField();
        domainField.setPromptText("如 example.com");
        TextField pathField = new TextField("/");
        pathField.setPromptText("如 /");
        CheckBox secureCb = new CheckBox("Secure (仅 HTTPS 传输)");
        CheckBox httpOnlyCb = new CheckBox("HttpOnly (不可被 JS 读取)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Value:"), 0, 1);
        grid.add(valueField, 1, 1);
        grid.add(new Label("Domain:"), 0, 2);
        grid.add(domainField, 1, 2);
        grid.add(new Label("Path:"), 0, 3);
        grid.add(pathField, 1, 3);
        grid.add(secureCb, 0, 4);
        grid.add(httpOnlyCb, 1, 4);
        pane.setContent(grid);

        // OK 按钮在 Name 非空前禁用
        Node okBtn = pane.lookupButton(ButtonType.OK);
        okBtn.setDisable(true);
        nameField.textProperty().addListener((obs, o, n) ->
                okBtn.setDisable(n == null || n.trim().isEmpty()));

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                String name = nameField.getText().trim();
                if (name.isEmpty()) {
                    return null;
                }
                CookieEntry entry = new CookieEntry();
                entry.setName(name);
                entry.setValue(valueField.getText());
                String domain = domainField.getText().trim();
                entry.setDomain(domain);
                String path = pathField.getText().trim();
                entry.setPath(path.isEmpty() ? "/" : path);
                entry.setSecure(secureCb.isSelected());
                entry.setHttpOnly(httpOnlyCb.isSelected());
                entry.setExpiry(0L); // Session Cookie, 关闭即失效
                return entry;
            }
            return null;
        });

        return dialog.showAndWait();
    }

    // ==================== 数据刷新 ====================

    /** 从服务重新加载当前集合的 Cookie 列表到表格。 */
    private void refreshTable(ObservableList<CookieEntry> data, Label statusLabel) {
        List<CookieEntry> cookies = cookieService.getAllCookiesFlat();
        data.setAll(cookies);
        if (statusLabel != null) {
            statusLabel.setText("当前集合: " + currentCollectionLabel()
                    + "    |    共 " + cookies.size() + " 条 Cookie");
        }
    }

    /** 当前集合 ID 的展示文本。 */
    private String currentCollectionLabel() {
        String id = cookieService.getCurrentCollectionId();
        if (id == null || id.isEmpty() || CookieService.GLOBAL_COLLECTION.equals(id)) {
            return "全局";
        }
        return id;
    }

    // ==================== 工具方法 ====================

    /** 格式化 Cookie 过期时间: 0 表示 Session Cookie。 */
    private String formatExpiry(CookieEntry entry) {
        if (entry == null) {
            return "";
        }
        long expiry = entry.getExpiry();
        if (expiry <= 0L) {
            return "Session";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expiry));
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

    private boolean confirm(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认");
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    // ==================== 内部类 ====================

    /** 只读复选框单元格 — 用于 Secure / HttpOnly 列的布尔展示。 */
    private static class ReadOnlyCheckBoxCell extends TableCell<CookieEntry, Boolean> {
        private final CheckBox checkBox = new CheckBox();

        ReadOnlyCheckBoxCell() {
            checkBox.setDisable(true);
            setAlignment(Pos.CENTER);
        }

        @Override
        protected void updateItem(Boolean item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
            } else {
                checkBox.setSelected(item != null && item);
                setGraphic(checkBox);
            }
        }
    }
}
