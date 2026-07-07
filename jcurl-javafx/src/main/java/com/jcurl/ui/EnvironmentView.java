package com.jcurl.ui;

import com.jcurl.model.Environment;
import com.jcurl.model.GlobalVariables;
import com.jcurl.plugin.model.component.Variable;
import com.jcurl.service.EnvironmentService;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 环境管理视图 — 管理 API 测试环境与全局变量。
 * <p>
 * 布局:
 * <pre>
 * ┌──────────────────────────────────────────────────┐
 * │ 环境: [ComboBox▼] [新建][删除][激活] | [保存环境]   │ ← 工具栏
 * ├──────────────────────────────────────────────────┤
 * │ 环境变量                                          │
 * │ [+ 添加变量] [- 删除选中]                          │
 * │ ┌──────┬───────┬───────┬───────────┬─────────┐   │
 * │ │ Key  │ Value │Secret │Description │ Enabled │   │
 * │ └──────┴───────┴───────┴───────────┴─────────┘   │
 * ├──────────────────────────────────────────────────┤
 * │ 全局变量                                          │
 * │ [+ 添加变量] [- 删除选中] [保存全局变量]            │
 * │ ┌──────┬───────┬───────┬───────────┬─────────┐   │
 * │ │ Key  │ Value │Secret │Description │ Enabled │   │
 * │ └──────┴───────┴───────┴───────────┴─────────┘   │
 * └──────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 功能:
 * <ul>
 *   <li>新建 / 删除 / 激活环境,环境变量实时编辑</li>
 *   <li>secret 变量值在表格中以 ****** 掩码显示,编辑时还原真实值</li>
 *   <li>独立的 全局变量 表格,跨环境生效</li>
 *   <li>所有 UI 数据更新通过 {@link Platform#runLater(Runnable)} 保证线程安全</li>
 * </ul>
 * <p>
 * 通过 {@link EnvironmentService} 管理环境与全局变量的持久化。
 */
@Lazy
@Component
public class EnvironmentView {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentView.class);

    private final EnvironmentService environmentService;

    /** 根布局 */
    private VBox root;

    /** 环境选择下拉框 */
    private ComboBox<Environment> environmentCombo;

    /** 环境变量表格 */
    private TableView<VariableRow> envVarTable;
    private final ObservableList<VariableRow> envVarData = FXCollections.observableArrayList();

    /** 全局变量表格 */
    private TableView<VariableRow> globalVarTable;
    private final ObservableList<VariableRow> globalVarData = FXCollections.observableArrayList();

    /** 当前编辑的环境(null 表示无) */
    private Environment currentEnvironment;

    /** 当前全局变量 */
    private GlobalVariables currentGlobals;

    /** 抑制下拉框程序化选中时触发的选择事件 */
    private boolean suppressSelection = false;

    public EnvironmentView(EnvironmentService environmentService) {
        this.environmentService = environmentService;
        buildView();
    }

    /** 返回根布局,供外部 Scene 使用 */
    public VBox getRoot() {
        return root;
    }

    // ==================== UI 构建 ====================

    private void buildView() {
        root = new VBox(8);
        root.getStyleClass().add("env-view");
        root.setPadding(new Insets(10));

        // 顶部工具栏
        root.getChildren().add(buildToolbar());

        // 环境变量区域
        VBox envSection = buildEnvVarSection();
        root.getChildren().add(envSection);

        // 分隔线
        Separator separator = new Separator();
        separator.setPadding(new Insets(4, 0, 4, 0));
        root.getChildren().add(separator);

        // 全局变量区域
        VBox globalsSection = buildGlobalsSection();
        VBox.setVgrow(globalsSection, Priority.ALWAYS);
        root.getChildren().add(globalsSection);

        // 初始加载数据(线程安全)
        refresh();
    }

    /** 顶部工具栏: 环境选择 + 新建/删除/激活 + 保存 */
    private ToolBar buildToolbar() {
        ToolBar toolbar = new ToolBar();
        toolbar.getStyleClass().add("env-toolbar");

        Label envLabel = new Label("环境:");
        envLabel.getStyleClass().add("sidebar-title");

        environmentCombo = new ComboBox<>();
        environmentCombo.getStyleClass().add("env-combo");
        environmentCombo.setPrefWidth(220);
        environmentCombo.setPromptText("选择环境");
        environmentCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Environment env) {
                return env == null ? "" : env.getName();
            }

            @Override
            public Environment fromString(String string) {
                return null;
            }
        });
        environmentCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Environment env, boolean empty) {
                super.updateItem(env, empty);
                setText(empty || env == null ? null : env.getName());
            }
        });
        environmentCombo.setOnAction(e -> onEnvironmentSelected());

        Button newEnvBtn = new Button("新建");
        newEnvBtn.setTooltip(new Tooltip("新建环境"));
        newEnvBtn.getStyleClass().add("env-button");
        newEnvBtn.setOnAction(e -> showNewEnvironmentDialog());

        Button deleteEnvBtn = new Button("删除");
        deleteEnvBtn.setTooltip(new Tooltip("删除当前选中的环境"));
        deleteEnvBtn.getStyleClass().add("env-button");
        deleteEnvBtn.setOnAction(e -> deleteCurrentEnvironment());

        Button activateBtn = new Button("激活");
        activateBtn.setTooltip(new Tooltip("将当前环境设为激活环境"));
        activateBtn.getStyleClass().add("env-button");
        activateBtn.setOnAction(e -> activateCurrentEnvironment());

        Button saveEnvBtn = new Button("保存环境");
        saveEnvBtn.setTooltip(new Tooltip("保存当前环境的变量"));
        saveEnvBtn.getStyleClass().add("env-button");
        saveEnvBtn.setOnAction(e -> saveCurrentEnvironment());

        toolbar.getItems().addAll(
                envLabel, environmentCombo, new Separator(),
                newEnvBtn, deleteEnvBtn, activateBtn, new Separator(),
                saveEnvBtn);
        return toolbar;
    }

    /** 环境变量区域: 标题 + 工具按钮 + TableView */
    private VBox buildEnvVarSection() {
        VBox section = new VBox(4);

        Label title = new Label("环境变量");
        title.getStyleClass().add("sidebar-title");

        envVarTable = createVariableTable(envVarData, "请选择或新建环境");

        Button addBtn = new Button("+ 添加变量");
        addBtn.getStyleClass().add("env-button");
        addBtn.setOnAction(e -> envVarData.add(new VariableRow("", "", false, "", true)));

        Button removeBtn = new Button("- 删除选中");
        removeBtn.getStyleClass().add("env-button");
        removeBtn.setOnAction(e -> envVarTable.getItems()
                .removeAll(envVarTable.getSelectionModel().getSelectedItems()));

        HBox tableToolbar = new HBox(8);
        tableToolbar.setPadding(new Insets(4, 0, 4, 0));
        tableToolbar.getChildren().addAll(addBtn, removeBtn);

        section.getChildren().addAll(title, tableToolbar, envVarTable);
        return section;
    }

    /** 全局变量区域: 标题 + 工具按钮 + TableView + 保存按钮 */
    private VBox buildGlobalsSection() {
        VBox section = new VBox(4);

        Label title = new Label("全局变量");
        title.getStyleClass().add("sidebar-title");

        globalVarTable = createVariableTable(globalVarData, "暂无全局变量");

        Button addBtn = new Button("+ 添加变量");
        addBtn.getStyleClass().add("env-button");
        addBtn.setOnAction(e -> globalVarData.add(new VariableRow("", "", false, "", true)));

        Button removeBtn = new Button("- 删除选中");
        removeBtn.getStyleClass().add("env-button");
        removeBtn.setOnAction(e -> globalVarTable.getItems()
                .removeAll(globalVarTable.getSelectionModel().getSelectedItems()));

        Button saveGlobalsBtn = new Button("保存全局变量");
        saveGlobalsBtn.getStyleClass().add("env-button");
        saveGlobalsBtn.setOnAction(e -> saveGlobals());

        HBox tableToolbar = new HBox(8);
        tableToolbar.setPadding(new Insets(4, 0, 4, 0));
        tableToolbar.getChildren().addAll(addBtn, removeBtn, saveGlobalsBtn);

        section.getChildren().addAll(title, tableToolbar, globalVarTable);
        return section;
    }

    /**
     * 创建变量表格(Key / Value / Secret / Description / Enabled)。
     *
     * @param data        表格数据源
     * @param placeholder 空表占位提示
     * @return 配置好的 TableView
     */
    private TableView<VariableRow> createVariableTable(ObservableList<VariableRow> data, String placeholder) {
        TableView<VariableRow> table = new TableView<>();
        table.getStyleClass().add("env-table");
        table.setEditable(true);
        table.setPlaceholder(new Label(placeholder));
        table.setItems(data);

        // Key 列(可编辑)
        TableColumn<VariableRow, String> keyCol = new TableColumn<>("键");
        keyCol.setCellValueFactory(p -> p.getValue().keyProperty());
        keyCol.setCellFactory(TextFieldTableCell.forTableColumn());
        keyCol.setPrefWidth(150);
        keyCol.setOnEditCommit(e -> e.getRowValue().setKey(e.getNewValue()));

        // Value 列(secret 时掩码显示,可编辑)
        TableColumn<VariableRow, String> valueCol = new TableColumn<>("值");
        valueCol.setCellValueFactory(p -> p.getValue().valueProperty());
        valueCol.setCellFactory(col -> new MaskedValueCell());
        valueCol.setPrefWidth(250);
        valueCol.setOnEditCommit(e -> e.getRowValue().setValue(e.getNewValue()));

        // Secret 列(复选框)
        TableColumn<VariableRow, Boolean> secretCol = new TableColumn<>("密钥");
        secretCol.setCellValueFactory(p -> p.getValue().secretProperty());
        secretCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(item != null && item);
                    checkBox.setOnAction(e -> {
                        VariableRow row = getTableRow() != null ? getTableRow().getItem() : null;
                        if (row != null) {
                            row.setSecret(checkBox.isSelected());
                            // 切换 secret 后刷新 Value 列的掩码显示
                            getTableView().refresh();
                        }
                    });
                    setGraphic(checkBox);
                }
            }
        });
        secretCol.setPrefWidth(70);

        // Description 列(可编辑)
        TableColumn<VariableRow, String> descCol = new TableColumn<>("描述");
        descCol.setCellValueFactory(p -> p.getValue().descriptionProperty());
        descCol.setCellFactory(TextFieldTableCell.forTableColumn());
        descCol.setPrefWidth(200);
        descCol.setOnEditCommit(e -> e.getRowValue().setDescription(e.getNewValue()));

        // Enabled 列(复选框)
        TableColumn<VariableRow, Boolean> enabledCol = new TableColumn<>("启用");
        enabledCol.setCellValueFactory(p -> p.getValue().enabledProperty());
        enabledCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(item != null && item);
                    checkBox.setOnAction(e -> {
                        VariableRow row = getTableRow() != null ? getTableRow().getItem() : null;
                        if (row != null) {
                            row.setEnabled(checkBox.isSelected());
                        }
                    });
                    setGraphic(checkBox);
                }
            }
        });
        enabledCol.setPrefWidth(70);

        table.getColumns().addAll(keyCol, valueCol, secretCol, descCol, enabledCol);
        table.setPrefHeight(220);
        return table;
    }

    // ==================== 数据加载 ====================

    /**
     * 刷新全部数据(线程安全)。可在任意线程调用。
     */
    public void refresh() {
        Platform.runLater(() -> {
            loadEnvironments();
            loadGlobals();
        });
    }

    /** 加载环境列表到下拉框,并选中当前激活环境。 */
    private void loadEnvironments() {
        List<Environment> environments = environmentService.listEnvironments();
        Environment previouslySelected = environmentCombo.getValue();

        suppressSelection = true;
        try {
            environmentCombo.getItems().setAll(environments);

            Environment active = environmentService.getActiveEnvironment();
            Environment target = null;

            // 优先选中激活环境
            if (active != null) {
                target = findEnvironment(environments, active.getId());
            }
            // 其次恢复之前选中项
            if (target == null && previouslySelected != null) {
                target = findEnvironment(environments, previouslySelected.getId());
            }

            if (target != null) {
                environmentCombo.setValue(target);
                currentEnvironment = target;
                loadEnvironmentVariables(target);
            } else {
                environmentCombo.setValue(null);
                currentEnvironment = null;
                envVarData.clear();
            }
        } finally {
            suppressSelection = false;
        }
    }

    private Environment findEnvironment(List<Environment> list, String id) {
        for (Environment e : list) {
            if (e.getId() != null && e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** 加载指定环境的变量到表格。 */
    private void loadEnvironmentVariables(Environment env) {
        envVarData.clear();
        if (env == null || env.getVariables() == null) {
            return;
        }
        for (Variable v : env.getVariables()) {
            envVarData.add(toRow(v));
        }
    }

    /** 加载全局变量到表格。 */
    private void loadGlobals() {
        currentGlobals = environmentService.loadGlobals();
        globalVarData.clear();
        if (currentGlobals != null && currentGlobals.getVariables() != null) {
            for (Variable v : currentGlobals.getVariables()) {
                globalVarData.add(toRow(v));
            }
        }
    }

    // ==================== 事件处理 ====================

    /** 下拉框选中环境时,加载其变量(从磁盘读取最新数据)。 */
    private void onEnvironmentSelected() {
        if (suppressSelection) {
            return;
        }
        Environment selected = environmentCombo.getValue();
        if (selected == null) {
            currentEnvironment = null;
            envVarData.clear();
            return;
        }
        // 从磁盘重新加载最新数据(避免使用过时的内存对象)
        Environment fresh = environmentService.loadEnvironment(selected.getId());
        currentEnvironment = fresh != null ? fresh : selected;
        loadEnvironmentVariables(currentEnvironment);
    }

    /** 新建环境对话框。 */
    private void showNewEnvironmentDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建环境");
        dialog.setHeaderText(null);
        dialog.setContentText("环境名称:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                Environment created = environmentService.createEnvironment(name.trim());
                log.info("新建环境: {}", name.trim());
                Platform.runLater(() -> {
                    loadEnvironments();
                    // 选中新创建的环境
                    Environment match = findEnvironment(environmentCombo.getItems(), created.getId());
                    if (match != null) {
                        suppressSelection = true;
                        try {
                            environmentCombo.setValue(match);
                            currentEnvironment = match;
                            loadEnvironmentVariables(match);
                        } finally {
                            suppressSelection = false;
                        }
                    }
                });
            }
        });
    }

    /** 删除当前选中的环境(带确认)。 */
    private void deleteCurrentEnvironment() {
        Environment selected = environmentCombo.getValue();
        if (selected == null) {
            showWarning("请先选择要删除的环境");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText(null);
        confirm.setContentText("确定要删除环境 \"" + selected.getName() + "\" 吗?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            environmentService.deleteEnvironment(selected.getId());
            log.info("删除环境: {}", selected.getName());
            Platform.runLater(this::loadEnvironments);
        }
    }

    /** 激活当前选中的环境。 */
    private void activateCurrentEnvironment() {
        Environment selected = environmentCombo.getValue();
        if (selected == null) {
            showWarning("请先选择要激活的环境");
            return;
        }
        environmentService.setActiveEnvironment(selected.getId());
        log.info("激活环境: {}", selected.getName());
        showInfo("已激活环境: " + selected.getName());
    }

    /** 保存当前环境的变量到磁盘。 */
    private void saveCurrentEnvironment() {
        if (currentEnvironment == null) {
            showWarning("没有可保存的环境,请先选择或新建环境");
            return;
        }
        currentEnvironment.setVariables(toVariableList(envVarData));
        environmentService.saveEnvironment(currentEnvironment);
        log.info("保存环境变量: {}", currentEnvironment.getName());
        showInfo("环境变量已保存");
    }

    /** 保存全局变量到磁盘。 */
    private void saveGlobals() {
        if (currentGlobals == null) {
            currentGlobals = new GlobalVariables();
        }
        currentGlobals.setVariables(toVariableList(globalVarData));
        environmentService.saveGlobals(currentGlobals);
        log.info("保存全局变量");
        showInfo("全局变量已保存");
    }

    // ==================== 工具方法 ====================

    /** 将 {@link Variable} 转为表格行。 */
    private VariableRow toRow(Variable v) {
        return new VariableRow(
                v.getKey() != null ? v.getKey() : "",
                v.getValue() != null ? v.getValue() : "",
                v.isSecret(),
                v.getDescription() != null ? v.getDescription() : "",
                v.isEnabled());
    }

    /** 将表格行集合转为 {@link Variable} 列表(过滤掉 Key 为空的行)。 */
    private List<Variable> toVariableList(ObservableList<VariableRow> rows) {
        List<Variable> list = new ArrayList<>();
        for (VariableRow row : rows) {
            if (row.getKey() == null || row.getKey().isBlank()) {
                continue;
            }
            Variable v = new Variable();
            v.setKey(row.getKey());
            v.setValue(row.getValue());
            v.setSecret(row.isSecret());
            v.setDescription(row.getDescription());
            v.setEnabled(row.isEnabled());
            list.add(v);
        }
        return list;
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("警告");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // ==================== 内部类 ====================

    /** 变量行数据(JavaFX 属性,支持表格绑定与编辑)。 */
    public static class VariableRow {
        private final SimpleStringProperty key = new SimpleStringProperty("");
        private final SimpleStringProperty value = new SimpleStringProperty("");
        private final SimpleBooleanProperty secret = new SimpleBooleanProperty(false);
        private final SimpleStringProperty description = new SimpleStringProperty("");
        private final SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);

        public VariableRow(String key, String value, boolean secret, String description, boolean enabled) {
            setKey(key);
            setValue(value);
            setSecret(secret);
            setDescription(description);
            setEnabled(enabled);
        }

        public String getKey() { return key.get(); }
        public void setKey(String v) { key.set(v); }
        public SimpleStringProperty keyProperty() { return key; }

        public String getValue() { return value.get(); }
        public void setValue(String v) { value.set(v); }
        public SimpleStringProperty valueProperty() { return value; }

        public boolean isSecret() { return secret.get(); }
        public void setSecret(boolean v) { secret.set(v); }
        public SimpleBooleanProperty secretProperty() { return secret; }

        public String getDescription() { return description.get(); }
        public void setDescription(String v) { description.set(v); }
        public SimpleStringProperty descriptionProperty() { return description; }

        public boolean isEnabled() { return enabled.get(); }
        public void setEnabled(boolean v) { enabled.set(v); }
        public SimpleBooleanProperty enabledProperty() { return enabled; }
    }

    /**
     * Value 列单元格 — secret 为 true 时掩码显示为 ******,编辑时显示真实值。
     * <p>
     * 参考标准 JavaFX EditingCell 模式,在 updateItem 中根据行数据的 secret 标志决定掩码。
     */
    private class MaskedValueCell extends TableCell<VariableRow, String> {
        private TextField textField;

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else {
                VariableRow row = getTableRow() != null ? getTableRow().getItem() : null;
                if (isEditing()) {
                    if (textField != null) {
                        textField.setText(item);
                    }
                    setText(null);
                    setGraphic(textField);
                } else {
                    if (row != null && row.isSecret() && item != null && !item.isEmpty()) {
                        setText("******");
                    } else {
                        setText(item);
                    }
                    setGraphic(null);
                }
            }
        }

        @Override
        public void startEdit() {
            if (!isEditable() || !getTableView().isEditable() || !getTableColumn().isEditable()) {
                return;
            }
            super.startEdit();
            if (textField == null) {
                createTextField();
            }
            textField.setText(getItem());
            setText(null);
            setGraphic(textField);
            textField.selectAll();
            Platform.runLater(textField::requestFocus);
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            VariableRow row = getTableRow() != null ? getTableRow().getItem() : null;
            if (row != null && row.isSecret() && getItem() != null && !getItem().isEmpty()) {
                setText("******");
            } else {
                setText(getItem());
            }
            setGraphic(null);
        }

        private void createTextField() {
            textField = new TextField(getItem());
            textField.setOnAction(e -> commitEdit(textField.getText()));
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused) {
                    commitEdit(textField.getText());
                }
            });
        }
    }
}
