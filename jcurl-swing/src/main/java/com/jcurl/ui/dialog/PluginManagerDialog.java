package com.jcurl.ui.dialog;

import com.jcurl.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 插件管理对话框 — 查看和管理已加载插件的全功能界面。
 * <p>
 * 功能:
 * <ul>
 *   <li><b>安装插件</b>: 通过 AWT {@link FileDialog} 选择 .java 或 .jar 文件,
 *       复制到插件目录并加载</li>
 *   <li><b>卸载插件</b>: 从注册表移除选中插件 (保留源文件)</li>
 *   <li><b>启用/禁用插件</b>: 注册/取消注册扩展点,保留插件实例</li>
 *   <li><b>重载插件/重载全部</b>: 先卸载再重新加载</li>
 *   <li><b>编辑默认插件</b>: 弹出代码编辑器,编辑 {@code DefaultPlugin.java} 模板,
 *       保存后自动编译并重载</li>
 *   <li><b>刷新</b>: 重新读取插件列表</li>
 * </ul>
 * <p>
 * 表格列: 名称 / 版本 / 状态 / 描述 / ID / 扩展点 / 文件路径。
 * 状态列着色: loaded=绿色, disabled=金色, failed=红色, unloaded=灰色。
 * <p>
 * 所有耗时操作 (安装/卸载/启用/禁用/重载) 均在 {@link SwingWorker} 后台线程执行,
 * 避免阻塞 EDT。
 * <p>
 * 注意: 使用 AWT {@link FileDialog} 而非 {@code JFileChooser}, 因为 JFileChooser 在
 * Windows Server 环境下构造时会调用 Windows Shell API, 容易无限阻塞 EDT。
 */
public class PluginManagerDialog extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(PluginManagerDialog.class);

    private final PluginManager pluginManager;
    private DefaultTableModel tableModel;
    private JTable table;
    private JLabel statusLabel;

    /** 插件目录 (从 PluginManager 获取, 与 application.yml 中 jcurl.plugin-dir 一致) */
    private final Path pluginDir;
    /** 默认插件源文件路径 */
    private final Path defaultPluginFile;

    /** 无插件时显示的占位文本 (该行不可操作) */
    private static final String EMPTY_PLACEHOLDER = "(无已加载插件)";

    /** 表格列索引 */
    private static final int COL_NAME = 0;
    private static final int COL_VERSION = 1;
    private static final int COL_STATUS = 2;
    private static final int COL_DESCRIPTION = 3;
    private static final int COL_ID = 4;
    private static final int COL_EXTENSION_POINTS = 5;
    private static final int COL_FILEPATH = 6;

    /** 状态值常量 */
    private static final String STATUS_LOADED = "loaded";
    private static final String STATUS_DISABLED = "disabled";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_UNLOADED = "unloaded";

    /**
     * 默认插件模板代码 — 保存为 {@code DefaultPlugin.java} 后编译加载。
     * 使用共享插件接口 (com.jcurl.plugin.*), 通过 @JcurlPlugin 注解提供元数据,
     * 实现 RequestInterceptor + ResponseInterceptor + VariableFunctionExtension + MetricsCollectorExtension 四个扩展点,
     * 展示全部四个扩展点的用法。
     */
    private static final String DEFAULT_PLUGIN_TEMPLATE =
            "import com.jcurl.plugin.model.component.Header;\n" +
            "import com.jcurl.plugin.model.dto.RequestConfig;\n" +
            "import com.jcurl.plugin.model.dto.ResponseData;\n" +
            "import com.jcurl.plugin.JcurlPlugin;\n" +
            "import com.jcurl.plugin.PluginContext;\n" +
            "import com.jcurl.plugin.extension.MetricsCollectorExtension;\n" +
            "import com.jcurl.plugin.extension.RequestInterceptor;\n" +
            "import com.jcurl.plugin.extension.ResponseInterceptor;\n" +
            "import com.jcurl.plugin.extension.VariableFunctionExtension;\n" +
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

    /**
     * @param parent       父窗口
     * @param pluginManager 插件管理器
     */
    public PluginManagerDialog(JFrame parent, PluginManager pluginManager) {
        super(parent, "插件管理", true);
        this.pluginManager = pluginManager;
        this.pluginDir = pluginManager.getPluginDir();
        this.defaultPluginFile = pluginDir.resolve("DefaultPlugin.java");
        initUI();
        loadPlugins();
        setSize(980, 540);
        setLocationRelativeTo(parent);
    }

    // ==================== UI 初始化 ====================

    private void initUI() {
        setLayout(new BorderLayout(5, 5));

        // ---- 顶部按钮栏 ----
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));

        JButton installBtn = new JButton("安装插件");
        installBtn.setToolTipText("选择 .java 或 .jar 文件,复制到插件目录并加载");
        installBtn.addActionListener(e -> installPlugin());

        JButton uninstallBtn = new JButton("卸载插件");
        uninstallBtn.setToolTipText("卸载选中插件 (从注册表移除,保留源文件)");
        uninstallBtn.addActionListener(e -> uninstallSelectedPlugin());

        JButton enableBtn = new JButton("启用插件");
        enableBtn.setToolTipText("启用选中插件 (重新注册扩展点)");
        enableBtn.addActionListener(e -> enableSelectedPlugin());

        JButton disableBtn = new JButton("禁用插件");
        disableBtn.setToolTipText("禁用选中插件 (取消注册扩展点,保留实例)");
        disableBtn.addActionListener(e -> disableSelectedPlugin());

        JButton reloadBtn = new JButton("重载插件");
        reloadBtn.setToolTipText("重载选中插件 (先卸载再加载)");
        reloadBtn.addActionListener(e -> reloadSelectedPlugin());

        JButton reloadAllBtn = new JButton("重载全部");
        reloadAllBtn.setToolTipText("重载所有插件");
        reloadAllBtn.addActionListener(e -> reloadAllPlugins());

        JButton editDefaultBtn = new JButton("编辑默认插件");
        editDefaultBtn.setToolTipText("编辑内置默认插件模板,保存后编译并重载");
        editDefaultBtn.addActionListener(e -> editDefaultPlugin());

        JButton refreshBtn = new JButton("刷新");
        refreshBtn.setToolTipText("刷新插件列表");
        refreshBtn.addActionListener(e -> loadPlugins());

        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dispose());

        buttonPanel.add(installBtn);
        buttonPanel.add(uninstallBtn);
        buttonPanel.add(enableBtn);
        buttonPanel.add(disableBtn);
        buttonPanel.add(reloadBtn);
        buttonPanel.add(reloadAllBtn);
        buttonPanel.add(editDefaultBtn);
        buttonPanel.add(refreshBtn);
        buttonPanel.add(closeBtn);
        add(buttonPanel, BorderLayout.NORTH);

        // ---- 中间插件表格 ----
        String[] columns = {"名称", "版本", "状态", "描述", "ID", "扩展点", "文件路径"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // 列宽设置
        table.getColumnModel().getColumn(COL_NAME).setPreferredWidth(120);
        table.getColumnModel().getColumn(COL_VERSION).setPreferredWidth(60);
        table.getColumnModel().getColumn(COL_STATUS).setPreferredWidth(70);
        table.getColumnModel().getColumn(COL_DESCRIPTION).setPreferredWidth(180);
        table.getColumnModel().getColumn(COL_ID).setPreferredWidth(140);
        table.getColumnModel().getColumn(COL_EXTENSION_POINTS).setPreferredWidth(160);
        table.getColumnModel().getColumn(COL_FILEPATH).setPreferredWidth(280);
        // 状态列自定义着色渲染器
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new StatusCellRenderer());
        add(new JScrollPane(table), BorderLayout.CENTER);

        // 右键菜单
        initContextMenu();

        // ---- 底部状态栏 ----
        statusLabel = new JLabel("就绪");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * 初始化表格右键菜单: 启用 / 禁用 / 重载 / 卸载。
     * 右键时自动选中光标所在行。
     */
    private void initContextMenu() {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem enableItem = new JMenuItem("启用插件");
        enableItem.addActionListener(e -> enableSelectedPlugin());
        JMenuItem disableItem = new JMenuItem("禁用插件");
        disableItem.addActionListener(e -> disableSelectedPlugin());
        JMenuItem reloadItem = new JMenuItem("重载插件");
        reloadItem.addActionListener(e -> reloadSelectedPlugin());
        JMenuItem uninstallItem = new JMenuItem("卸载插件");
        uninstallItem.addActionListener(e -> uninstallSelectedPlugin());

        popup.add(enableItem);
        popup.add(disableItem);
        popup.add(reloadItem);
        popup.addSeparator();
        popup.add(uninstallItem);
        table.setComponentPopupMenu(popup);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeSelectRowUnderPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeSelectRowUnderPopup(e);
            }

            private void maybeSelectRowUnderPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row);
                }
            }
        });
    }

    // ==================== 按钮操作 ====================

    /**
     * 安装插件: 用 AWT FileDialog 选择 .java 或 .jar 文件,
     * 复制到插件目录后加载。使用 SwingWorker 后台执行。
     */
    private void installPlugin() {
        FileDialog fileDialog = new FileDialog(this, "选择插件文件 (.java 或 .jar)", FileDialog.LOAD);
        fileDialog.setFilenameFilter((dir, name) ->
                name.toLowerCase().endsWith(".jar") || name.toLowerCase().endsWith(".java"));
        fileDialog.setVisible(true);
        String selectedFile = fileDialog.getFile();
        String selectedDir = fileDialog.getDirectory();
        if (selectedFile == null || selectedDir == null) {
            return; // 用户取消
        }
        File sourceFile = new File(selectedDir, selectedFile);
        if (!sourceFile.exists()) {
            JOptionPane.showMessageDialog(this, "文件不存在: " + sourceFile,
                    "安装失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fileName = sourceFile.getName().toLowerCase();
        if (!fileName.endsWith(".jar") && !fileName.endsWith(".java")) {
            JOptionPane.showMessageDialog(this, "仅支持 .java 和 .jar 文件",
                    "安装失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 显示加载中提示
        JOptionPane pane = new JOptionPane("正在安装插件 " + sourceFile.getName() + "...",
                JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                new Object[]{"请稍候"}, "请稍候");
        final JDialog loadingDialog = pane.createDialog(this, "安装中");
        loadingDialog.setModal(false);
        loadingDialog.setVisible(true);

        final File finalSourceFile = sourceFile;
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // 确保插件目录存在
                Files.createDirectories(pluginDir);
                // 复制到插件目录 (覆盖同名文件)
                Path target = pluginDir.resolve(finalSourceFile.getName());
                Files.copy(finalSourceFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                // 根据文件类型加载
                String name = finalSourceFile.getName().toLowerCase();
                if (name.endsWith(".jar")) {
                    pluginManager.loadJarPlugin(target);
                } else {
                    pluginManager.loadJavaPlugin(target);
                }
                return finalSourceFile.getName();
            }

            @Override
            protected void done() {
                loadingDialog.dispose();
                try {
                    String name = get();
                    setStatus("插件安装成功: " + name);
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            "插件安装成功: " + name, "安装成功", JOptionPane.INFORMATION_MESSAGE);
                    loadPlugins();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.error("安装插件失败: {}", finalSourceFile.getName(), ex);
                    setStatus("安装失败: " + cause.getMessage());
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            "安装插件失败:\n" + cause.getMessage(),
                            "安装失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /**
     * 卸载选中插件: 卸载前需用户确认, 在后台线程执行。
     */
    private void uninstallSelectedPlugin() {
        String pluginId = getSelectedPluginId();
        if (pluginId == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要卸载插件 \"" + pluginId + "\" 吗?\n(源文件将保留, 可重新加载)",
                "确认卸载", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        final String finalPluginId = pluginId;
        runPluginTask("卸载插件", () -> pluginManager.unloadPlugin(finalPluginId));
    }

    /**
     * 启用选中插件: 重新注册扩展点。
     */
    private void enableSelectedPlugin() {
        String pluginId = getSelectedPluginId();
        if (pluginId == null) {
            return;
        }
        final String finalPluginId = pluginId;
        runPluginTask("启用插件", () -> pluginManager.enablePlugin(finalPluginId));
    }

    /**
     * 禁用选中插件: 取消注册扩展点, 保留实例。
     */
    private void disableSelectedPlugin() {
        String pluginId = getSelectedPluginId();
        if (pluginId == null) {
            return;
        }
        final String finalPluginId = pluginId;
        runPluginTask("禁用插件", () -> pluginManager.disablePlugin(finalPluginId));
    }

    /**
     * 重载选中插件: 先卸载再加载。
     */
    private void reloadSelectedPlugin() {
        String pluginId = getSelectedPluginId();
        if (pluginId == null) {
            return;
        }
        final String finalPluginId = pluginId;
        runPluginTask("重载插件", () -> pluginManager.reloadPlugin(finalPluginId));
    }

    /**
     * 重载所有插件: 卸载后重新加载全部。
     */
    private void reloadAllPlugins() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要重载所有插件吗?", "确认重载全部", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        runPluginTask("重载全部插件", () -> pluginManager.reloadAll());
    }

    /**
     * 编辑默认插件: 打开代码编辑器对话框, 编辑 DefaultPlugin.java 模板。
     * 编辑器关闭后刷新插件列表。
     */
    private void editDefaultPlugin() {
        if (!pluginManager.isCompilerAvailable()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Java 编译器不可用 (可能运行在 JRE 环境)。\n.java 插件将无法编译。\n是否继续编辑?",
                    "编译器不可用", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        DefaultPluginEditorDialog editor = new DefaultPluginEditorDialog(this);
        editor.setVisible(true);
        // 编辑器关闭后刷新列表
        loadPlugins();
    }

    // ==================== 表格数据 ====================

    /**
     * 从 PluginManager 加载插件列表并填充表格。
     * 无插件时显示占位行。
     */
    private void loadPlugins() {
        tableModel.setRowCount(0);
        List<PluginManager.PluginInfo> plugins = pluginManager.getLoadedPlugins();
        for (PluginManager.PluginInfo info : plugins) {
            String extPoints = (info.getExtensionPoints() != null && !info.getExtensionPoints().isEmpty())
                    ? String.join(", ", info.getExtensionPoints()) : "";
            tableModel.addRow(new Object[]{
                    info.getName(),
                    info.getVersion(),
                    info.getStatus(),
                    info.getDescription(),
                    info.getId(),
                    extPoints,
                    info.getFilePath()
            });
        }
        if (plugins.isEmpty()) {
            tableModel.addRow(new Object[]{EMPTY_PLACEHOLDER, "", "", "", "", "", ""});
        }

        int count = plugins.size();
        String compilerHint = pluginManager.isCompilerAvailable()
                ? "" : " (编译器不可用, .java 插件将被跳过)";
        setStatus("已加载 " + count + " 个插件" + compilerHint);
    }

    /**
     * 获取表格中选中行的插件 ID。
     * 未选中或选中占位行时弹出提示并返回 null。
     */
    private String getSelectedPluginId() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "请先在表格中选择一个插件",
                    "未选择", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        String pluginId = (String) tableModel.getValueAt(modelRow, COL_ID);
        if (pluginId == null || pluginId.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前行不是有效插件, 无法操作",
                    "无效选择", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return pluginId;
    }

    /** 更新底部状态栏文本 */
    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ==================== 后台任务工具 ====================

    /** 可抛出受检异常的任务接口 */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * 在后台线程执行插件操作, 完成后刷新列表并更新状态栏。
     * 失败时弹出错误对话框。
     *
     * @param actionName 操作名称 (用于状态栏和错误提示)
     * @param task       要执行的任务
     */
    private void runPluginTask(final String actionName, final ThrowingRunnable task) {
        setStatus(actionName + " 中...");
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                task.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    setStatus(actionName + " 完成");
                    loadPlugins();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.error("{} 失败", actionName, ex);
                    setStatus(actionName + " 失败: " + cause.getMessage());
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            actionName + "失败:\n" + cause.getMessage(),
                            "操作失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // ==================== 内部类: 状态列渲染器 ====================

    /**
     * 状态列自定义渲染器 — 根据状态值着色:
     * loaded=绿色, disabled=金色, failed=红色, unloaded=灰色。
     * 选中行使用默认选中前景色。
     */
    private static class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (isSelected) {
                return c;
            }
            String status = value == null ? "" : value.toString();
            Color color;
            if (STATUS_LOADED.equals(status)) {
                color = new Color(0, 153, 0);        // 绿色
            } else if (STATUS_DISABLED.equals(status)) {
                color = new Color(204, 153, 0);      // 金色
            } else if (STATUS_FAILED.equals(status)) {
                color = new Color(204, 0, 0);        // 红色
            } else if (STATUS_UNLOADED.equals(status)) {
                color = Color.GRAY;                   // 灰色
            } else {
                color = c.getForeground();
            }
            setForeground(color);
            return c;
        }
    }

    // ==================== 内部类: 默认插件编辑器 ====================

    /**
     * 默认插件编辑器对话框 — 编辑 DefaultPlugin.java 模板, 保存后编译并重载。
     * <p>
     * 如果 {@code DefaultPlugin.java} 已存在, 加载其内容;
     * 否则加载内置模板代码。保存时写入文件并调用 PluginManager 重载。
     */
    private class DefaultPluginEditorDialog extends JDialog {

        private final JTextArea codeArea;

        DefaultPluginEditorDialog(JDialog owner) {
            super(owner, "编辑默认插件", true);
            setLayout(new BorderLayout(5, 5));

            // ---- 顶部提示 ----
            JLabel hintLabel = new JLabel(
                    "编辑默认插件代码, 保存后将编译并重载 (需要 JDK 环境):");
            hintLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
            add(hintLabel, BorderLayout.NORTH);

            // ---- 代码编辑区 ----
            codeArea = new JTextArea(loadInitialContent());
            codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            codeArea.setTabSize(4);
            JScrollPane scrollPane = new JScrollPane(codeArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            add(scrollPane, BorderLayout.CENTER);

            // ---- 底部按钮 ----
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
            JButton saveBtn = new JButton("保存并重载");
            saveBtn.setToolTipText("将代码保存为 DefaultPlugin.java 并重新编译加载");
            saveBtn.addActionListener(e -> saveAndReload());
            JButton cancelBtn = new JButton("取消");
            cancelBtn.addActionListener(e -> dispose());
            buttonPanel.add(saveBtn);
            buttonPanel.add(cancelBtn);
            add(buttonPanel, BorderLayout.SOUTH);

            setSize(720, 560);
            setLocationRelativeTo(owner);
        }

        /**
         * 加载初始内容: 如果 DefaultPlugin.java 已存在则读取其内容, 否则使用内置模板。
         */
        private String loadInitialContent() {
            if (Files.exists(defaultPluginFile)) {
                try {
                    return new String(Files.readAllBytes(defaultPluginFile), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn("读取默认插件文件失败, 使用模板: {}", e.getMessage());
                }
            }
            return DEFAULT_PLUGIN_TEMPLATE;
        }

        /**
         * 保存代码到 DefaultPlugin.java 并重载插件。
         * 如果插件已加载则 reload, 否则 loadJavaPlugin。
         * 使用 SwingWorker 后台执行编译加载, 避免阻塞 EDT。
         */
        private void saveAndReload() {
            final String code = codeArea.getText();
            if (code == null || code.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "代码不能为空", "保存失败", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // 显示加载中提示
            JOptionPane pane = new JOptionPane("正在保存并重载默认插件...",
                    JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                    new Object[]{"请稍候"}, "请稍候");
            final JDialog loadingDialog = pane.createDialog(this, "处理中");
            loadingDialog.setModal(false);
            loadingDialog.setVisible(true);

            SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() throws Exception {
                    // 确保插件目录存在
                    Files.createDirectories(pluginDir);
                    // 写入源码
                    Files.write(defaultPluginFile, code.getBytes(StandardCharsets.UTF_8));
                    // 判断插件是否已加载: 已加载则 reload, 否则 loadJavaPlugin
                    boolean exists = false;
                    for (PluginManager.PluginInfo info : pluginManager.getLoadedPlugins()) {
                        if ("DefaultPlugin".equals(info.getId())) {
                            exists = true;
                            break;
                        }
                    }
                    if (exists) {
                        pluginManager.reloadPlugin("DefaultPlugin");
                    } else {
                        pluginManager.loadJavaPlugin(defaultPluginFile);
                    }
                    return Boolean.TRUE;
                }

                @Override
                protected void done() {
                    loadingDialog.dispose();
                    try {
                        get();
                        setStatus("默认插件已保存并重载");
                        JOptionPane.showMessageDialog(DefaultPluginEditorDialog.this,
                                "默认插件已保存并重载", "保存成功", JOptionPane.INFORMATION_MESSAGE);
                        dispose();
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        log.error("默认插件保存/重载失败", ex);
                        setStatus("默认插件保存/重载失败: " + cause.getMessage());
                        JOptionPane.showMessageDialog(DefaultPluginEditorDialog.this,
                                "保存并重载失败:\n" + cause.getMessage(),
                                "操作失败", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            worker.execute();
        }
    }
}
