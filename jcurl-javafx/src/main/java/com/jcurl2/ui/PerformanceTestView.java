package com.jcurl2.ui;

import com.jcurl2.perf.*;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 性能测试视图 — 提供性能测试配置、实时监控与报告导出。
 * <p>
 * 布局:
 * <pre>
 * ┌────────────────────────────────────────┐
 * │ 配置区: 负载模型/VU数/持续时间/...      │
 * ├────────────────────────────────────────┤
 * │ [Start] [Cancel] [Export HTML/JSON/CSV]│
 * ├──────────────────┬─────────────────────┤
 * │  RPS 趋势图      │   延迟趋势图         │
 * ├──────────────────┴─────────────────────┤
 * │  指标摘要表(总数/成功率/P50/P90/...)    │
 * └────────────────────────────────────────┘
 * </pre>
 */
@Lazy
@Component
public class PerformanceTestView {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTestView.class);

    private final PerformanceTestService perfService;
    private final ReportExporter reportExporter;

    private VBox root;

    // 配置控件
    private ComboBox<LoadModel> loadModelCombo;
    private Spinner<Integer> maxVusSpinner;
    private Spinner<Integer> durationSpinner;
    private Spinner<Integer> rampUpSpinner;
    private Spinner<Integer> stairsStepsSpinner;
    private Spinner<Integer> waveCyclesSpinner;
    private Spinner<Integer> intervalSpinner;

    // 操作按钮
    private Button startButton;
    private Button cancelButton;

    // 图表
    private LineChart<Number, Number> rpsChart;
    private LineChart<Number, Number> latencyChart;
    private XYChart.Series<Number, Number> rpsSeries;
    private XYChart.Series<Number, Number> p50Series;
    private XYChart.Series<Number, Number> p90Series;
    private XYChart.Series<Number, Number> p95Series;
    private XYChart.Series<Number, Number> p99Series;

    // 指标表格
    private TableView<MetricsRow> metricsTable;
    private final ObservableList<MetricsRow> metricsData = FXCollections.observableArrayList();

    // 历史快照(用于报告导出)
    private final List<MetricsSnapshot> historySnapshots = new ArrayList<>();
    private MetricsSnapshot finalSnapshot;

    // 图表时间轴计数器
    private int chartTimeCounter = 0;

    public PerformanceTestView(PerformanceTestService perfService, ReportExporter reportExporter) {
        this.perfService = perfService;
        this.reportExporter = reportExporter;
        buildView();
    }

    public VBox getRoot() {
        return root;
    }

    private void buildView() {
        root = new VBox(8);
        root.getStyleClass().add("perf-view");
        root.setPadding(new Insets(8));

        root.getChildren().addAll(
                buildConfigPanel(),
                buildActionPanel(),
                buildChartsArea(),
                buildMetricsTable()
        );
    }

    /** 配置面板 */
    private TitledPane buildConfigPanel() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));

        loadModelCombo = new ComboBox<>(FXCollections.observableArrayList(LoadModel.values()));
        loadModelCombo.setValue(LoadModel.CONSTANT);
        loadModelCombo.setOnAction(e -> updateModelSpecificFields());

        maxVusSpinner = new Spinner<>(1, 500, 10, 1);
        maxVusSpinner.setEditable(true);

        durationSpinner = new Spinner<>(1, 3600, 60, 1);
        durationSpinner.setEditable(true);

        rampUpSpinner = new Spinner<>(0, 600, 10, 1);
        rampUpSpinner.setEditable(true);

        stairsStepsSpinner = new Spinner<>(1, 20, 4, 1);
        stairsStepsSpinner.setEditable(true);

        waveCyclesSpinner = new Spinner<>(1, 20, 3, 1);
        waveCyclesSpinner.setEditable(true);

        intervalSpinner = new Spinner<>(0, 10000, 0, 100);
        intervalSpinner.setEditable(true);

        int row = 0;
        grid.add(new Label("负载模型:"), 0, row);
        grid.add(loadModelCombo, 1, row);
        grid.add(new Label("最大 VU 数:"), 2, row);
        grid.add(maxVusSpinner, 3, row);
        row++;

        grid.add(new Label("持续时间(秒):"), 0, row);
        grid.add(durationSpinner, 1, row);
        grid.add(new Label("请求间隔(ms):"), 2, row);
        grid.add(intervalSpinner, 3, row);
        row++;

        grid.add(new Label("渐增时间(秒):"), 0, row);
        grid.add(rampUpSpinner, 1, row);
        row++;

        grid.add(new Label("阶梯步数:"), 0, row);
        grid.add(stairsStepsSpinner, 1, row);
        row++;

        grid.add(new Label("波浪周期数:"), 0, row);
        grid.add(waveCyclesSpinner, 1, row);

        updateModelSpecificFields();

        TitledPane pane = new TitledPane("测试配置", grid);
        pane.setExpanded(true);
        return pane;
    }

    /** 操作按钮面板 */
    private HBox buildActionPanel() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 0, 4, 0));

        startButton = new Button("▶ 开始测试");
        startButton.getStyleClass().add("send-button");
        startButton.setOnAction(e -> startTest());

        cancelButton = new Button("■ 取消");
        cancelButton.getStyleClass().add("cancel-button");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> perfService.cancelTest());

        Button exportHtmlBtn = new Button("导出 HTML");
        exportHtmlBtn.setOnAction(e -> exportReport("html"));

        Button exportJsonBtn = new Button("导出 JSON");
        exportJsonBtn.setOnAction(e -> exportReport("json"));

        Button exportCsvBtn = new Button("导出 CSV");
        exportCsvBtn.setOnAction(e -> exportReport("csv"));

        bar.getChildren().addAll(startButton, cancelButton,
                new Separator(),
                exportHtmlBtn, exportJsonBtn, exportCsvBtn);
        return bar;
    }

    /** 图表区域 */
    private HBox buildChartsArea() {
        HBox chartsBox = new HBox(8);
        chartsBox.setPrefHeight(250);

        // RPS 图表
        NumberAxis rpsXAxis = new NumberAxis();
        rpsXAxis.setLabel("时间(秒)");
        rpsXAxis.setAutoRanging(true);
        NumberAxis rpsYAxis = new NumberAxis();
        rpsYAxis.setLabel("RPS");
        rpsYAxis.setAutoRanging(true);
        rpsChart = new LineChart<>(rpsXAxis, rpsYAxis);
        rpsChart.setTitle("RPS 趋势");
        rpsChart.setCreateSymbols(false);
        rpsChart.setAnimated(false);
        rpsSeries = new XYChart.Series<>();
        rpsSeries.setName("RPS");
        rpsChart.getData().add(rpsSeries);
        HBox.setHgrow(rpsChart, Priority.ALWAYS);

        // 延迟图表
        NumberAxis latXAxis = new NumberAxis();
        latXAxis.setLabel("时间(秒)");
        latXAxis.setAutoRanging(true);
        NumberAxis latYAxis = new NumberAxis();
        latYAxis.setLabel("延迟(ms)");
        latYAxis.setAutoRanging(true);
        latencyChart = new LineChart<>(latXAxis, latYAxis);
        latencyChart.setTitle("延迟趋势");
        latencyChart.setCreateSymbols(false);
        latencyChart.setAnimated(false);
        p50Series = new XYChart.Series<>();
        p50Series.setName("P50");
        p90Series = new XYChart.Series<>();
        p90Series.setName("P90");
        p95Series = new XYChart.Series<>();
        p95Series.setName("P95");
        p99Series = new XYChart.Series<>();
        p99Series.setName("P99");
        latencyChart.getData().addAll(p50Series, p90Series, p95Series, p99Series);
        HBox.setHgrow(latencyChart, Priority.ALWAYS);

        chartsBox.getChildren().addAll(rpsChart, latencyChart);
        return chartsBox;
    }

    /** 指标表格 */
    private TableView<MetricsRow> buildMetricsTable() {
        metricsTable = new TableView<>();
        metricsTable.setPrefHeight(200);
        metricsTable.setItems(metricsData);

        addColumn("指标", "name", 200);
        addColumn("值", "value", 300);

        // 初始行
        metricsData.addAll(
                new MetricsRow("总请求数", "—"),
                new MetricsRow("成功请求", "—"),
                new MetricsRow("失败请求", "—"),
                new MetricsRow("RPS", "—"),
                new MetricsRow("平均延迟", "—"),
                new MetricsRow("P50", "—"),
                new MetricsRow("P90", "—"),
                new MetricsRow("P95", "—"),
                new MetricsRow("P99", "—"),
                new MetricsRow("最小延迟", "—"),
                new MetricsRow("最大延迟", "—"),
                new MetricsRow("错误率", "—"),
                new MetricsRow("运行时间", "—")
        );

        return metricsTable;
    }

    private <T> void addColumn(String title, String property, double width) {
        TableColumn<MetricsRow, String> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        metricsTable.getColumns().add(col);
    }

    // ==================== 测试控制 ====================

    private void startTest() {
        // 优先从 supplier 获取当前请求配置
        if (requestConfigSupplier != null) {
            currentRequestConfigJson = requestConfigSupplier.get();
        }
        if (currentRequestConfigJson == null) {
            showWarning("请先在请求构建器中选择或配置一个请求");
            return;
        }

        LoadModel model = loadModelCombo.getValue();
        PerformanceTestConfig config = PerformanceTestConfig.builder()
                .requestConfigJson(currentRequestConfigJson)
                .loadModel(model)
                .maxVus(maxVusSpinner.getValue())
                .durationSeconds(durationSpinner.getValue())
                .rampUpSeconds(rampUpSpinner.getValue())
                .stairsSteps(stairsStepsSpinner.getValue())
                .waveCycles(waveCyclesSpinner.getValue())
                .requestIntervalMs(intervalSpinner.getValue())
                .build();

        // 清空图表和历史
        rpsSeries.getData().clear();
        p50Series.getData().clear();
        p90Series.getData().clear();
        p95Series.getData().clear();
        p99Series.getData().clear();
        historySnapshots.clear();
        chartTimeCounter = 0;

        startButton.setDisable(true);
        cancelButton.setDisable(false);

        perfService.startTest(config, this::onMetricsUpdate, this::onTestComplete);
    }

    /** 实时指标回调(每秒调用) */
    private void onMetricsUpdate(MetricsSnapshot snapshot) {
        Platform.runLater(() -> {
            historySnapshots.add(snapshot);
            int t = ++chartTimeCounter;

            // 更新图表(限制数据点数量,保留最近 120 个)
            rpsSeries.getData().add(new XYChart.Data<>(t, snapshot.getRps()));
            p50Series.getData().add(new XYChart.Data<>(t, snapshot.getP50()));
            p90Series.getData().add(new XYChart.Data<>(t, snapshot.getP90()));
            p95Series.getData().add(new XYChart.Data<>(t, snapshot.getP95()));
            p99Series.getData().add(new XYChart.Data<>(t, snapshot.getP99()));

            trimChartSeries(120);

            // 更新指标表格
            updateMetricsTable(snapshot);
        });
    }

    /** 测试完成回调 */
    private void onTestComplete(MetricsSnapshot snapshot) {
        Platform.runLater(() -> {
            finalSnapshot = snapshot;
            updateMetricsTable(snapshot);
            startButton.setDisable(false);
            cancelButton.setDisable(true);
        });
    }

    // ==================== 报告导出 ====================

    private void exportReport(String format) {
        if (finalSnapshot == null && historySnapshots.isEmpty()) {
            showWarning("没有可导出的测试数据");
            return;
        }

        MetricsSnapshot snapshot = finalSnapshot != null ? finalSnapshot : historySnapshots.get(historySnapshots.size() - 1);

        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialFileName("perf-report-" + System.currentTimeMillis() + "." + format);
        File file = fileChooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;

        try {
            Path outputPath = file.toPath();
            switch (format) {
                case "html" -> reportExporter.exportHtml(snapshot, historySnapshots,
                        PerformanceTestConfig.builder().requestConfigJson("{}").build(), outputPath);
                case "json" -> reportExporter.exportJson(snapshot, historySnapshots,
                        PerformanceTestConfig.builder().requestConfigJson("{}").build(), outputPath);
                case "csv" -> reportExporter.exportCsv(snapshot, historySnapshots, outputPath);
            }
            showInfo("报告已导出: " + file.getName());
        } catch (Exception e) {
            log.error("报告导出失败", e);
            showError("导出失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    private String currentRequestConfigJson;

    /** 设置当前请求配置 JSON(由 MainView 在请求构建器发送时注入) */
    public void setRequestConfigJson(String json) {
        this.currentRequestConfigJson = json;
    }

    /** 设置请求配置 JSON 供应者(由 MainView 注入,点击开始时调用) */
    public void setRequestConfigSupplier(java.util.function.Supplier<String> supplier) {
        this.requestConfigSupplier = supplier;
    }

    private java.util.function.Supplier<String> requestConfigSupplier;

    private void updateModelSpecificFields() {
        LoadModel model = loadModelCombo.getValue();
        rampUpSpinner.setDisable(model != LoadModel.RAMP_UP);
        stairsStepsSpinner.setDisable(model != LoadModel.STAIRS);
        waveCyclesSpinner.setDisable(model != LoadModel.WAVE);
    }

    private void trimChartSeries(int maxPoints) {
        while (rpsSeries.getData().size() > maxPoints) rpsSeries.getData().remove(0);
        while (p50Series.getData().size() > maxPoints) p50Series.getData().remove(0);
        while (p90Series.getData().size() > maxPoints) p90Series.getData().remove(0);
        while (p95Series.getData().size() > maxPoints) p95Series.getData().remove(0);
        while (p99Series.getData().size() > maxPoints) p99Series.getData().remove(0);
    }

    private void updateMetricsTable(MetricsSnapshot s) {
        metricsData.get(0).setValue(String.valueOf(s.getTotalRequests()));
        metricsData.get(1).setValue(String.valueOf(s.getSuccessCount()));
        metricsData.get(2).setValue(String.valueOf(s.getFailureCount()));
        metricsData.get(3).setValue(String.format("%.1f", s.getRps()));
        metricsData.get(4).setValue(String.format("%.1f ms", s.getAvgLatency()));
        metricsData.get(5).setValue(String.format("%.1f ms", s.getP50()));
        metricsData.get(6).setValue(String.format("%.1f ms", s.getP90()));
        metricsData.get(7).setValue(String.format("%.1f ms", s.getP95()));
        metricsData.get(8).setValue(String.format("%.1f ms", s.getP99()));
        metricsData.get(9).setValue(s.getMinLatency() + " ms");
        metricsData.get(10).setValue(s.getMaxLatency() + " ms");
        metricsData.get(11).setValue(String.format("%.2f%%", s.getErrorRate()));
        metricsData.get(12).setValue(String.format("%.1f s", s.getElapsedSeconds()));
    }

    private void showWarning(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    // ==================== 表格数据行 ====================

    public static class MetricsRow {
        private final String name;
        private String value;

        public MetricsRow(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
