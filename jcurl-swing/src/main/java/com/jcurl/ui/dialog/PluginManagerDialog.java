package com.jcurl.ui.dialog;

import com.jcurl.plugin.PluginInfo;
import com.jcurl.plugin.PluginManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

/**
 * 插件管理对话框 — 查看、安装、卸载已加载插件。
 * <p>
 * 表格列: 名称 / 版本 / 状态 / 描述 / ID / 文件路径。
 * 操作:
 * - 安装插件: 选择 .jar 文件, 调用 {@link PluginManager#loadPlugin(File)} 加载。
 * - 卸载插件: 选中表格行后, 调用 {@link PluginManager#unloadPlugin(String)} 卸载 (pluginId = name-version)。
 * 加载/卸载均在后台线程执行, 避免阻塞 EDT; 错误用 JOptionPane 提示。
 * <p>
 * 注意: 使用 AWT {@link FileDialog} 而非 {@link JFileChooser}, 因为 JFileChooser 在
 * Windows Server 环境下构造时会调用 Windows Shell API 解析系统文件夹, 容易无限阻塞 EDT。
 */
public class PluginManagerDialog extends JDialog {
    private final PluginManager pluginManager;
    private DefaultTableModel tableModel;
    private JTable table;

    /** 无插件时显示的占位文本 (该行不可卸载) */
    private static final String EMPTY_PLACEHOLDER = "(无已加载插件)";

    /** 表格列索引 */
    private static final int COL_NAME = 0;
    private static final int COL_VERSION = 1;
    private static final int COL_STATUS = 2;
    private static final int COL_DESCRIPTION = 3;
    private static final int COL_ID = 4;
    private static final int COL_FILEPATH = 5;

    public PluginManagerDialog(JFrame parent, PluginManager pluginManager) {
        super(parent, "插件管理", true);
        this.pluginManager = pluginManager;
        initUI();
        loadPlugins();
        setSize(750, 460);
        setLocationRelativeTo(parent);
    }

    private void initUI() {
        setLayout(new BorderLayout());

        String[] columns = {"名称", "版本", "状态", "描述", "ID", "文件路径"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowHeight(24);
        // 列宽设置
        table.getColumnModel().getColumn(COL_NAME).setPreferredWidth(120);
        table.getColumnModel().getColumn(COL_VERSION).setPreferredWidth(70);
        table.getColumnModel().getColumn(COL_STATUS).setPreferredWidth(70);
        table.getColumnModel().getColumn(COL_DESCRIPTION).setPreferredWidth(180);
        table.getColumnModel().getColumn(COL_ID).setPreferredWidth(150);
        table.getColumnModel().getColumn(COL_FILEPATH).setPreferredWidth(280);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // 右键菜单 (卸载)
        initContextMenu();

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton installButton = new JButton("安装插件");
        installButton.setToolTipText("选择 .jar 文件加载为新插件");
        installButton.addActionListener(e -> installPlugin());
        JButton uninstallButton = new JButton("卸载插件");
        uninstallButton.setToolTipText("卸载表格中选中的插件");
        uninstallButton.addActionListener(e -> uninstallSelectedPlugin());
        JButton refreshButton = new JButton("刷新");
        refreshButton.addActionListener(e -> loadPlugins());
        JButton closeButton = new JButton("关闭");
        closeButton.addActionListener(e -> dispose());
        buttonPanel.add(installButton);
        buttonPanel.add(uninstallButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(closeButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * 初始化表格右键菜单: 卸载插件。
     * 右键时自动选中光标所在行, 便于后续卸载。
     */
    private void initContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem uninstallItem = new JMenuItem("卸载插件");
        uninstallItem.addActionListener(e -> uninstallSelectedPlugin());
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

    /**
     * 安装插件: 用 AWT FileDialog 选择 .jar 文件 (避免 JFileChooser 在 Windows Server 卡死),
     * 在后台线程中调用 PluginManager.loadPlugin 加载 (避免 ServiceLoader/URLClassLoader 阻塞 EDT)。
     * 加载期间显示进度提示; 成功后刷新列表, 失败用 JOptionPane 提示错误。
     */
    private void installPlugin() {
        // 用 AWT FileDialog 代替 JFileChooser — Windows Server 上更稳定
        FileDialog fileDialog = new FileDialog(this, "选择插件 JAR 文件", FileDialog.LOAD);
        fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".jar"));
        fileDialog.setVisible(true);
        String selectedFile = fileDialog.getFile();
        String selectedDir = fileDialog.getDirectory();
        if (selectedFile == null || selectedDir == null) {
            return; // 用户取消
        }
        File jarFile = new File(selectedDir, selectedFile);
        if (!jarFile.exists()) {
            JOptionPane.showMessageDialog(this, "文件不存在: " + jarFile,
                    "安装失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 后台线程加载插件, 避免 ServiceLoader / URLClassLoader / onLoad 阻塞 EDT
        final File finalJarFile = jarFile;
        JOptionPane pane = new JOptionPane("正在加载插件 " + jarFile.getName() + "...",
                JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                new Object[]{"请稍候"}, "请稍候");
        JDialog loadingDialog = pane.createDialog(this, "安装中");
        loadingDialog.setModal(false);
        loadingDialog.setVisible(true);

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                pluginManager.loadPlugin(finalJarFile);
                return finalJarFile.getName();
            }

            @Override
            protected void done() {
                loadingDialog.dispose();
                try {
                    String name = get();
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            "插件安装成功: " + name, "安装成功", JOptionPane.INFORMATION_MESSAGE);
                    loadPlugins();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            "加载插件失败:\n" + ex.getMessage(),
                            "安装失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /**
     * 卸载表格中选中行的插件。
     * 从 ID 列取 pluginId (name-version), 调用 PluginManager.unloadPlugin 卸载。
     * 卸载前需用户确认; 卸载在后台线程执行 (onUnload/classLoader.close 可能耗时), 完成后刷新列表。
     */
    private void uninstallSelectedPlugin() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "请先在表格中选择要卸载的插件",
                    "未选择", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        String pluginId = (String) tableModel.getValueAt(modelRow, COL_ID);
        if (pluginId == null || pluginId.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前行不是有效插件, 无法卸载",
                    "无法卸载", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要卸载插件 \"" + pluginId + "\" 吗?",
                "确认卸载", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        // 后台线程卸载, 避免 onUnload / classLoader.close 阻塞 EDT
        final String finalPluginId = pluginId;
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                pluginManager.unloadPlugin(finalPluginId);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            "插件已卸载: " + finalPluginId,
                            "卸载成功", JOptionPane.INFORMATION_MESSAGE);
                    loadPlugins();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            "卸载插件失败:\n" + ex.getMessage(),
                            "卸载失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /**
     * 从 PluginManager 加载插件列表并填充表格。
     * 无插件时显示占位行。
     */
    private void loadPlugins() {
        tableModel.setRowCount(0);
        List<PluginInfo> plugins = pluginManager.getLoadedPlugins();
        for (PluginInfo info : plugins) {
            String pluginId = info.getName() + "-" + info.getVersion();
            tableModel.addRow(new Object[]{
                    info.getName(),
                    info.getVersion(),
                    info.getStatus(),
                    info.getDescription(),
                    pluginId,
                    info.getFilePath()
            });
        }
        if (plugins.isEmpty()) {
            tableModel.addRow(new Object[]{EMPTY_PLACEHOLDER, "", "", "", "", ""});
        }
    }
}
