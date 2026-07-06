package com.jpostman.ui.dialog;

import com.jpostman.model.Variable;
import com.jpostman.service.EnvironmentService;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局变量管理对话框 — 管理跨环境共享的全局变量。
 * <p>
 * 非 Spring 管理的组件, 由 MainFrame 通过 new 创建, 构造时传入依赖。
 * 模态显示 (setModal(true))。
 * <p>
 * v2 适配: 变量以 Variable 表示, 表格包含启用/Key/Value/Secret 四列,
 * Save All 策略: 收集表格数据为 List&lt;Variable&gt;, 调用 saveGlobalVariables 全量保存。
 * <p>
 * 布局:
 * - 变量表格 (启用, Key, Value, Secret), 全部可编辑
 * - 底部按钮: [Add][Delete][Save All][Close]
 */
public class GlobalVariableDialog extends JDialog {

    private final transient EnvironmentService environmentService;

    private final DefaultTableModel variablesTableModel;
    private JTable table;

    /** 是否在界面中显示敏感变量的真实值 (false 时敏感值掩码显示为 ******) */
    private boolean showSecretValues = false;

    public GlobalVariableDialog(JFrame parent, EnvironmentService environmentService) {
        super(parent, "全局变量", true);
        this.environmentService = environmentService;

        setLayout(new BorderLayout());

        // ===== 变量表格 =====
        variablesTableModel = new DefaultTableModel(new String[]{"启用", "Key", "Value", "敏感"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0 || columnIndex == 3) {
                    return Boolean.class;
                }
                return String.class;
            }
        };
        table = new JTable(variablesTableModel);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(250);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);

        // Value 列自定义渲染: 对应行 Secret=true 且隐藏模式下显示掩码 ******, 实际值保留在模型中
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                if (!showSecretValues) {
                    int modelRow = tbl.convertRowIndexToModel(row);
                    Boolean secret = (Boolean) variablesTableModel.getValueAt(modelRow, 3);
                    if (Boolean.TRUE.equals(secret)) {
                        setText("******");
                    }
                }
                return this;
            }
        });

        // 敏感列表头 tooltip 说明
        TableColumn secretColumn = table.getColumnModel().getColumn(3);
        TableCellRenderer defaultHeaderRenderer = table.getTableHeader().getDefaultRenderer();
        secretColumn.setHeaderRenderer((tbl, value, isSelected, hasFocus, row, column) -> {
            Component c = defaultHeaderRenderer.getTableCellRendererComponent(
                    tbl, value, isSelected, hasFocus, row, column);
            if (c instanceof JComponent) {
                ((JComponent) c).setToolTipText("勾选后该变量值在界面中掩码显示,防止敏感信息泄露");
            }
            return c;
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("全局变量"));
        add(tableScroll, BorderLayout.CENTER);

        // ===== 底部按钮 =====
        JButton addButton = new JButton("添加");
        JButton deleteButton = new JButton("删除");
        JButton toggleSecretButton = new JButton("显示敏感值");
        JButton saveAllButton = new JButton("全部保存");
        JButton closeButton = new JButton("关闭");
        toggleSecretButton.setToolTipText("切换是否显示敏感变量的真实值");

        addButton.addActionListener(e -> {
            variablesTableModel.addRow(new Object[]{Boolean.TRUE, "", "", Boolean.FALSE});
            int lastRow = variablesTableModel.getRowCount() - 1;
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
            table.setRowSelectionInterval(lastRow, lastRow);
            table.editCellAt(lastRow, 1);
            table.transferFocus();
        });

        deleteButton.addActionListener(e -> deleteSelectedRows());
        toggleSecretButton.addActionListener(e -> {
            showSecretValues = !showSecretValues;
            toggleSecretButton.setText(showSecretValues ? "隐藏敏感值" : "显示敏感值");
            table.repaint();
        });

        saveAllButton.addActionListener(e -> saveAllVariables());
        closeButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new MigLayout("insets 4", "[][]push[][][]", ""));
        buttonPanel.add(addButton, "");
        buttonPanel.add(deleteButton, "");
        buttonPanel.add(toggleSecretButton, "");
        buttonPanel.add(saveAllButton, "");
        buttonPanel.add(closeButton, "");
        add(buttonPanel, BorderLayout.SOUTH);

        // 变量表格右键上下文菜单
        initTableContextMenu();

        setSize(650, 420);
        setLocationRelativeTo(parent);

        loadVariables();
    }

    /**
     * 从服务加载所有全局变量到表格。
     */
    private void loadVariables() {
        variablesTableModel.setRowCount(0);
        List<Variable> variables = environmentService.getGlobalVariables();
        for (Variable v : variables) {
            variablesTableModel.addRow(new Object[]{
                    v.isEnabled(),
                    v.getKey() != null ? v.getKey() : "",
                    v.getValue() != null ? v.getValue() : "",
                    v.isSecret()
            });
        }
    }

    /**
     * 保存所有变量。收集表格数据为 List&lt;Variable&gt;, 调用 saveGlobalVariables 全量覆盖。
     */
    private void saveAllVariables() {
        try {
            List<Variable> variables = new ArrayList<>();
            for (int i = 0; i < variablesTableModel.getRowCount(); i++) {
                Variable v = new Variable();
                v.setEnabled((Boolean) variablesTableModel.getValueAt(i, 0));
                v.setKey((String) variablesTableModel.getValueAt(i, 1));
                v.setValue((String) variablesTableModel.getValueAt(i, 2));
                v.setSecret((Boolean) variablesTableModel.getValueAt(i, 3));
                variables.add(v);
            }
            environmentService.saveGlobalVariables(variables);

            JOptionPane.showMessageDialog(this, "全局变量保存成功。",
                    "已保存", JOptionPane.INFORMATION_MESSAGE);
            loadVariables();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "保存全局变量失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== 右键上下文菜单 ====================

    /**
     * 初始化变量表格的右键上下文菜单。
     * 菜单项: 复制键/值/键值对, 在上方/下方插入行, 删除选中行。
     * 右键时自动选中光标所在行。
     */
    private void initTableContextMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem copyKey = new JMenuItem("复制键");
        JMenuItem copyValue = new JMenuItem("复制值");
        JMenuItem copyPair = new JMenuItem("复制键值对");
        JMenuItem insertAbove = new JMenuItem("在上方插入行");
        JMenuItem insertBelow = new JMenuItem("在下方插入行");
        JMenuItem deleteRow = new JMenuItem("删除选中行");

        popupMenu.add(copyKey);
        popupMenu.add(copyValue);
        popupMenu.add(copyPair);
        popupMenu.addSeparator();
        popupMenu.add(insertAbove);
        popupMenu.add(insertBelow);
        popupMenu.addSeparator();
        popupMenu.add(deleteRow);

        table.setComponentPopupMenu(popupMenu);

        // 右键时自动选中光标所在行
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeSelectRowForPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeSelectRowForPopup(e);
            }

            private void maybeSelectRowForPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        table.setRowSelectionInterval(row, row);
                    }
                }
            }
        });

        copyKey.addActionListener(e -> copyCellToClipboard(1));   // Key 列索引 = 1
        copyValue.addActionListener(e -> copyCellToClipboard(2)); // Value 列索引 = 2
        copyPair.addActionListener(e -> copyPairToClipboard());
        insertAbove.addActionListener(e -> insertRow(true));
        insertBelow.addActionListener(e -> insertRow(false));
        deleteRow.addActionListener(e -> deleteSelectedRows());
    }

    /**
     * 复制选中行指定列的值到系统剪贴板。
     *
     * @param columnIndex 列索引 (1=Key, 2=Value)
     */
    private void copyCellToClipboard(int columnIndex) {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        Object value = variablesTableModel.getValueAt(row, columnIndex);
        String text = value != null ? value.toString() : "";
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * 复制选中行的 "Key: Value" 到系统剪贴板。
     */
    private void copyPairToClipboard() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        String key = (String) variablesTableModel.getValueAt(row, 1);
        String value = (String) variablesTableModel.getValueAt(row, 2);
        String text = (key != null ? key : "") + ": " + (value != null ? value : "");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * 在选中行上方 (above=true) 或下方 (above=false) 插入一个空行。
     * 未选中行时追加到末尾。
     *
     * @param above 是否在选中行上方插入
     */
    private void insertRow(boolean above) {
        int row = table.getSelectedRow();
        int insertAt;
        if (row < 0) {
            insertAt = variablesTableModel.getRowCount();
        } else {
            insertAt = above ? row : row + 1;
        }
        variablesTableModel.insertRow(insertAt, new Object[]{Boolean.TRUE, "", "", Boolean.FALSE});
        table.setRowSelectionInterval(insertAt, insertAt);
        table.editCellAt(insertAt, 1);
        table.transferFocus();
    }

    /**
     * 删除所有选中行。
     */
    private void deleteSelectedRows() {
        int[] selectedRows = table.getSelectedRows();
        for (int i = selectedRows.length - 1; i >= 0; i--) {
            variablesTableModel.removeRow(selectedRows[i]);
        }
    }

    /**
     * 显示对话框。静态便捷方法。
     *
     * @param parent             父窗口
     * @param environmentService 环境服务
     */
    public static void showDialog(Frame parent, EnvironmentService environmentService) {
        GlobalVariableDialog dialog = new GlobalVariableDialog((JFrame) parent, environmentService);
        dialog.setVisible(true);
    }
}
