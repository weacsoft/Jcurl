package com.jpostman.ui.dialog;

import com.jpostman.model.CollectionFile;
import com.jpostman.model.LoadTestConfig;
import com.jpostman.model.LoadTestResult;
import com.jpostman.model.LoadTestResult.MetricPoint;
import com.jpostman.model.RequestNode;
import com.jpostman.service.CollectionService;
import com.jpostman.service.LoadTestService;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 性能测试对话框 — 配置测试参数、启动测试、查看实时监控和最终结果。
 * <p>
 * 非 Spring 管理的组件, 由 MainFrame 通过 new 创建, 构造时传入
 * {@link LoadTestService} 和 {@link CollectionService}。
 * 非模态显示 (setModal(false)), 允许测试在后台运行时用户继续操作主窗口。
 * <p>
 * 布局:
 * <ul>
 *   <li>顶部: 测试配置区 (集合选择、请求列表、VU 数量、持续时间、负载模型、请求间隔、通过条件)</li>
 *   <li>中部: 控制按钮 (开始/停止/暂停/继续/关闭)</li>
 *   <li>中下部: 实时监控区 (当前 VU 数、平均响应时间、错误率、吞吐量)</li>
 *   <li>底部: 测试结果区 (总请求数、成功/失败数、平均/最小/最大响应时间、P50/P90/P95/P99、通过/失败)</li>
 * </ul>
 */
public class LoadTestDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(LoadTestDialog.class);

    private final transient LoadTestService loadTestService;
    private final transient CollectionService collectionService;

    // ===== 配置区组件 =====
    private final JComboBox<String> collectionCombo;
    private final DefaultListModel<RequestNode> requestListModel;
    private final JList<RequestNode> requestList;
    private final JSpinner vuSpinner;
    private final JSpinner durationSpinner;
    private final JComboBox<String> loadProfileCombo;
    private final JSpinner intervalSpinner;
    private final JTextField maxAvgRtField;
    private final JTextField maxErrorRateField;

    // ===== 控制按钮 =====
    private final JButton startButton;
    private final JButton stopButton;
    private final JButton pauseButton;
    private final JButton resumeButton;
    private final JButton closeButton;

    // ===== 实时监控标签 =====
    private final JLabel rtCurrentVuLabel;
    private final JLabel rtAvgRtLabel;
    private final JLabel rtErrorRateLabel;
    private final JLabel rtThroughputLabel;

    // ===== 结果标签 =====
    private final JLabel totalReqLabel;
    private final JLabel successReqLabel;
    private final JLabel failedReqLabel;
    private final JLabel avgRtResultLabel;
    private final JLabel minRtLabel;
    private final JLabel maxRtLabel;
    private final JLabel p50Label;
    private final JLabel p90Label;
    private final JLabel p95Label;
    private final JLabel p99Label;
    private final JLabel passFailLabel;

    /** 集合下拉框项对应的 ID 列表 (与 collectionCombo 索引对齐, 第一项 "请选择" 为 null) */
    private final List<String> collectionIds = new ArrayList<>();

    public LoadTestDialog(JFrame parent, LoadTestService loadTestService,
                          CollectionService collectionService) {
        super(parent, "性能测试", false);
        this.loadTestService = loadTestService;
        this.collectionService = collectionService;

        // 初始化配置区组件
        collectionCombo = new JComboBox<>();
        requestListModel = new DefaultListModel<>();
        requestList = new JList<>(requestListModel);
        vuSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1));
        durationSpinner = new JSpinner(new SpinnerNumberModel(60, 1, 3600, 1));
        loadProfileCombo = new JComboBox<>(new String[]{"fixed", "rampup", "spike", "peak"});
        intervalSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 60000, 100));
        maxAvgRtField = new JTextField("0", 8);
        maxErrorRateField = new JTextField("0", 8);

        // 初始化控制按钮
        startButton = new JButton("开始测试");
        stopButton = new JButton("停止");
        pauseButton = new JButton("暂停");
        resumeButton = new JButton("继续");
        closeButton = new JButton("关闭");

        // 初始化实时监控标签
        rtCurrentVuLabel = new JLabel("0");
        rtAvgRtLabel = new JLabel("0 ms");
        rtErrorRateLabel = new JLabel("0.0 %");
        rtThroughputLabel = new JLabel("0 req/s");

        // 初始化结果标签
        totalReqLabel = new JLabel("-");
        successReqLabel = new JLabel("-");
        failedReqLabel = new JLabel("-");
        avgRtResultLabel = new JLabel("-");
        minRtLabel = new JLabel("-");
        maxRtLabel = new JLabel("-");
        p50Label = new JLabel("-");
        p90Label = new JLabel("-");
        p95Label = new JLabel("-");
        p99Label = new JLabel("-");
        passFailLabel = new JLabel("-");

        initUI();
        setupEventHandlers();
        loadCollections();

        setSize(750, 780);
        setMinimumSize(new Dimension(650, 700));
        setLocationRelativeTo(parent);
    }

    // ==================== UI 构建 ====================

    private void initUI() {
        setLayout(new BorderLayout(5, 5));

        // ===== 顶部: 测试配置区 =====
        JPanel configPanel = new JPanel(new MigLayout("fill, wrap 2", "[][grow]", "[][][grow][][][]"));
        configPanel.setBorder(BorderFactory.createTitledBorder("测试配置"));

        // 集合选择
        configPanel.add(new JLabel("集合:"), "");
        configPanel.add(collectionCombo, "grow, wrap");

        // 请求列表
        requestList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        requestList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof RequestNode) {
                    RequestNode node = (RequestNode) value;
                    String url = node.getUrl() != null ? node.getUrl() : "";
                    setText("[" + node.getMethod() + "] " + node.getName() + " - " + url);
                }
                return this;
            }
        });
        JScrollPane requestScroll = new JScrollPane(requestList);
        requestScroll.setPreferredSize(new Dimension(500, 120));

        JButton selectAllButton = new JButton("全选");
        selectAllButton.addActionListener(e -> {
            int size = requestListModel.size();
            if (size > 0) {
                requestList.setSelectionInterval(0, size - 1);
            }
        });
        JButton deselectAllButton = new JButton("取消全选");
        deselectAllButton.addActionListener(e -> requestList.clearSelection());

        JPanel requestButtonPanel = new JPanel(new MigLayout("insets 0", "[]push[]", ""));
        requestButtonPanel.add(selectAllButton, "");
        requestButtonPanel.add(deselectAllButton, "");

        JPanel requestPanel = new JPanel(new BorderLayout(3, 3));
        requestPanel.add(new JLabel("请求列表 (可多选):"), BorderLayout.NORTH);
        requestPanel.add(requestScroll, BorderLayout.CENTER);
        requestPanel.add(requestButtonPanel, BorderLayout.SOUTH);
        configPanel.add(requestPanel, "span, grow, wrap");

        // VU 数量和持续时间
        JPanel vuPanel = new JPanel(new MigLayout("insets 0", "[][]30[][]", ""));
        vuPanel.add(new JLabel("虚拟用户数:"), "");
        vuPanel.add(vuSpinner, "wmin 80");
        vuPanel.add(new JLabel("持续时间(秒):"), "");
        vuPanel.add(durationSpinner, "wmin 80");
        configPanel.add(vuPanel, "span, grow, wrap");

        // 负载模型和请求间隔
        JPanel profilePanel = new JPanel(new MigLayout("insets 0", "[][]30[][]", ""));
        profilePanel.add(new JLabel("负载模型:"), "");
        profilePanel.add(loadProfileCombo, "wmin 100");
        profilePanel.add(new JLabel("请求间隔(ms):"), "");
        profilePanel.add(intervalSpinner, "wmin 80");
        configPanel.add(profilePanel, "span, grow, wrap");

        // 通过条件
        JPanel criteriaPanel = new JPanel(new MigLayout("insets 0", "[][]30[][]", ""));
        criteriaPanel.add(new JLabel("平均响应时间上限(ms):"), "");
        criteriaPanel.add(maxAvgRtField, "wmin 80");
        criteriaPanel.add(new JLabel("错误率上限(%):"), "");
        criteriaPanel.add(maxErrorRateField, "wmin 80");
        configPanel.add(criteriaPanel, "span, grow");

        add(configPanel, BorderLayout.NORTH);

        // ===== 中部: 控制按钮 + 实时监控 =====
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

        // 控制按钮
        JPanel controlPanel = new JPanel(new MigLayout("insets 2", "push[][][][][]push", ""));
        stopButton.setEnabled(false);
        pauseButton.setEnabled(false);
        resumeButton.setEnabled(false);
        controlPanel.add(startButton, "");
        controlPanel.add(stopButton, "");
        controlPanel.add(pauseButton, "");
        controlPanel.add(resumeButton, "");
        controlPanel.add(closeButton, "");
        centerPanel.add(controlPanel, BorderLayout.NORTH);

        // 实时监控区
        JPanel monitorPanel = new JPanel(new MigLayout("fill, wrap 4", "[grow][grow][grow][grow]", "[]"));
        monitorPanel.setBorder(BorderFactory.createTitledBorder("实时监控"));

        monitorPanel.add(createMetricBox("当前 VU 数", rtCurrentVuLabel), "grow");
        monitorPanel.add(createMetricBox("平均响应时间", rtAvgRtLabel), "grow");
        monitorPanel.add(createMetricBox("错误率", rtErrorRateLabel), "grow");
        monitorPanel.add(createMetricBox("吞吐量", rtThroughputLabel), "grow");

        centerPanel.add(monitorPanel, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        // ===== 底部: 测试结果区 =====
        JPanel resultPanel = new JPanel(new MigLayout("fill, wrap 4", "[grow][grow][grow][grow]", "[][][]"));
        resultPanel.setBorder(BorderFactory.createTitledBorder("测试结果"));

        resultPanel.add(createResultBox("总请求数", totalReqLabel), "grow");
        resultPanel.add(createResultBox("成功数", successReqLabel), "grow");
        resultPanel.add(createResultBox("失败数", failedReqLabel), "grow");
        resultPanel.add(createResultBox("平均响应时间", avgRtResultLabel), "grow");

        resultPanel.add(createResultBox("最小响应时间", minRtLabel), "grow");
        resultPanel.add(createResultBox("最大响应时间", maxRtLabel), "grow");
        resultPanel.add(createResultBox("P50", p50Label), "grow");
        resultPanel.add(createResultBox("P90", p90Label), "grow");

        resultPanel.add(createResultBox("P95", p95Label), "grow");
        resultPanel.add(createResultBox("P99", p99Label), "grow");
        resultPanel.add(createResultBox("测试结果", passFailLabel), "span 2, grow");

        add(resultPanel, BorderLayout.SOUTH);
    }

    /**
     * 创建监控指标显示框 (带标题的标签)。
     */
    private JPanel createMetricBox(String title, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        valueLabel.setFont(valueLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        valueLabel.setHorizontalAlignment(JLabel.CENTER);
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 创建结果指标显示框。
     */
    private JPanel createResultBox(String title, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        valueLabel.setFont(valueLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        valueLabel.setHorizontalAlignment(JLabel.CENTER);
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    // ==================== 事件处理 ====================

    private void setupEventHandlers() {
        // 集合选择变化时加载请求列表
        collectionCombo.addActionListener(e -> {
            int index = collectionCombo.getSelectedIndex();
            if (index >= 0 && index < collectionIds.size()) {
                String collectionId = collectionIds.get(index);
                if (collectionId != null) {
                    loadRequests(collectionId);
                } else {
                    requestListModel.clear();
                }
            }
        });

        // 开始测试
        startButton.addActionListener(e -> startTest());

        // 停止测试
        stopButton.addActionListener(e -> {
            loadTestService.stop();
            statusTestStopped();
        });

        // 暂停
        pauseButton.addActionListener(e -> {
            loadTestService.pause();
            pauseButton.setEnabled(false);
            resumeButton.setEnabled(true);
        });

        // 继续
        resumeButton.addActionListener(e -> {
            loadTestService.resume();
            resumeButton.setEnabled(false);
            pauseButton.setEnabled(true);
        });

        // 关闭
        closeButton.addActionListener(e -> {
            if (loadTestService.isRunning()) {
                int confirm = JOptionPane.showConfirmDialog(this,
                        "测试正在进行中, 确定要关闭吗?",
                        "确认关闭", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
                loadTestService.stop();
            }
            dispose();
        });

        // 窗口关闭时停止测试
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (loadTestService.isRunning()) {
                    loadTestService.stop();
                }
            }
        });
    }

    // ==================== 数据加载 ====================

    /**
     * 加载所有集合到下拉框。
     */
    private void loadCollections() {
        collectionCombo.removeAllItems();
        collectionIds.clear();
        collectionCombo.addItem("请选择集合");
        collectionIds.add(null);
        try {
            List<CollectionFile> collections = collectionService.getAllCollections();
            if (collections != null) {
                for (CollectionFile c : collections) {
                    collectionCombo.addItem(c.getName());
                    collectionIds.add(c.getId());
                }
            }
        } catch (Exception e) {
            log.warn("加载集合列表失败", e);
        }
    }

    /**
     * 加载指定集合中的所有请求到列表。
     */
    private void loadRequests(String collectionId) {
        requestListModel.clear();
        try {
            List<RequestNode> requests = collectionService.getAllRequests(collectionId);
            if (requests != null) {
                for (RequestNode req : requests) {
                    requestListModel.addElement(req);
                }
            }
        } catch (Exception e) {
            log.warn("加载请求列表失败", e);
        }
    }

    // ==================== 测试执行 ====================

    /**
     * 从 UI 收集配置并启动测试。
     */
    private void startTest() {
        // 验证请求选择
        List<RequestNode> selectedRequests = requestList.getSelectedValuesList();
        if (selectedRequests.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请至少选择一个请求进行测试。",
                    "未选择请求", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 构建配置
        LoadTestConfig config = new LoadTestConfig();
        config.setVirtualUsers((Integer) vuSpinner.getValue());
        config.setDuration((Integer) durationSpinner.getValue());
        config.setLoadProfile((String) loadProfileCombo.getSelectedItem());
        config.setRequestInterval((Integer) intervalSpinner.getValue());
        config.setRequests(new ArrayList<>(selectedRequests));

        try {
            long maxRt = Long.parseLong(maxAvgRtField.getText().trim());
            config.setMaxAvgResponseTime(maxRt);
        } catch (NumberFormatException e) {
            config.setMaxAvgResponseTime(0);
        }
        try {
            double maxErr = Double.parseDouble(maxErrorRateField.getText().trim());
            config.setMaxErrorRate(maxErr);
        } catch (NumberFormatException e) {
            config.setMaxErrorRate(0);
        }

        // 重置结果显示
        resetResults();

        // 切换按钮状态
        statusTestRunning();

        // 在后台线程执行测试
        SwingWorker<LoadTestResult, Void> worker = new SwingWorker<LoadTestResult, Void>() {
            @Override
            protected LoadTestResult doInBackground() {
                return loadTestService.execute(config, new LoadTestService.LoadTestListener() {
                    @Override
                    public void onMetric(MetricPoint point) {
                        SwingUtilities.invokeLater(() -> updateRealtimeMetrics(point));
                    }

                    @Override
                    public void onComplete(LoadTestResult result) {
                        SwingUtilities.invokeLater(() -> showResults(result));
                    }
                });
            }

            @Override
            protected void done() {
                statusTestStopped();
                try {
                    LoadTestResult result = get();
                    showResults(result);
                } catch (Exception e) {
                    log.error("性能测试执行异常", e);
                    JOptionPane.showMessageDialog(LoadTestDialog.this,
                            "测试执行异常: " + e.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // ==================== UI 状态管理 ====================

    /**
     * 测试运行中: 禁用配置和开始按钮, 启用停止和暂停按钮。
     */
    private void statusTestRunning() {
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        pauseButton.setEnabled(true);
        resumeButton.setEnabled(false);
        setConfigEnabled(false);
    }

    /**
     * 测试停止: 启用配置和开始按钮, 禁用停止和暂停/继续按钮。
     */
    private void statusTestStopped() {
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        pauseButton.setEnabled(false);
        resumeButton.setEnabled(false);
        setConfigEnabled(true);
    }

    /**
     * 启用/禁用所有配置区组件。
     */
    private void setConfigEnabled(boolean enabled) {
        collectionCombo.setEnabled(enabled);
        requestList.setEnabled(enabled);
        vuSpinner.setEnabled(enabled);
        durationSpinner.setEnabled(enabled);
        loadProfileCombo.setEnabled(enabled);
        intervalSpinner.setEnabled(enabled);
        maxAvgRtField.setEnabled(enabled);
        maxErrorRateField.setEnabled(enabled);
    }

    /**
     * 重置结果显示。
     */
    private void resetResults() {
        rtCurrentVuLabel.setText("0");
        rtAvgRtLabel.setText("0 ms");
        rtErrorRateLabel.setText("0.0 %");
        rtThroughputLabel.setText("0 req/s");

        totalReqLabel.setText("-");
        successReqLabel.setText("-");
        failedReqLabel.setText("-");
        avgRtResultLabel.setText("-");
        minRtLabel.setText("-");
        maxRtLabel.setText("-");
        p50Label.setText("-");
        p90Label.setText("-");
        p95Label.setText("-");
        p99Label.setText("-");
        passFailLabel.setText("-");
        passFailLabel.setForeground(new java.awt.Color(0, 0, 0));
    }

    /**
     * 更新实时监控指标。
     */
    private void updateRealtimeMetrics(MetricPoint point) {
        rtCurrentVuLabel.setText(String.valueOf(point.currentVUs));
        rtAvgRtLabel.setText(String.format("%.1f ms", point.avgResponseTime));
        rtErrorRateLabel.setText(String.format("%.2f %%", point.errorRate));
        rtThroughputLabel.setText(String.format("%.1f req/s", point.throughput));
    }

    /**
     * 显示最终测试结果。
     */
    private void showResults(LoadTestResult result) {
        totalReqLabel.setText(String.valueOf(result.getTotalRequests()));
        successReqLabel.setText(String.valueOf(result.getSuccessRequests()));
        failedReqLabel.setText(String.valueOf(result.getFailedRequests()));
        avgRtResultLabel.setText(String.format("%.1f ms", result.getAverageResponseTime()));
        minRtLabel.setText(result.getMinResponseTime() + " ms");
        maxRtLabel.setText(result.getMaxResponseTime() + " ms");
        p50Label.setText(result.getPercentile(50) + " ms");
        p90Label.setText(result.getPercentile(90) + " ms");
        p95Label.setText(result.getPercentile(95) + " ms");
        p99Label.setText(result.getPercentile(99) + " ms");

        if (result.isPassed()) {
            passFailLabel.setText("通过");
            passFailLabel.setForeground(new java.awt.Color(0, 128, 0));
        } else {
            String reason = result.getFailureReason() != null ? result.getFailureReason() : "未通过";
            passFailLabel.setText("<html><b>失败</b><br><small>" + reason + "</small></html>");
            passFailLabel.setForeground(new java.awt.Color(200, 0, 0));
        }
    }

    // ==================== 静态便捷方法 ====================

    /**
     * 显示对话框。
     *
     * @param parent           父窗口
     * @param loadTestService  性能测试服务
     * @param collectionService 集合服务
     */
    public static void showDialog(Frame parent, LoadTestService loadTestService,
                                  CollectionService collectionService) {
        LoadTestDialog dialog = new LoadTestDialog((JFrame) parent, loadTestService, collectionService);
        dialog.setVisible(true);
    }
}
