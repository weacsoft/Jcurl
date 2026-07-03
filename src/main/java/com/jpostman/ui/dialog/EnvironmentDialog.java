package com.jpostman.ui.dialog;

import com.jpostman.model.EnvironmentFile;
import com.jpostman.model.Variable;
import com.jpostman.service.EnvironmentService;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
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
 * 环境管理对话框 — 管理多套环境及其变量。
 * <p>
 * 非 Spring 管理的组件, 由 MainFrame 通过 new 创建, 构造时传入依赖。
 * 模态显示 (setModal(true)), 关闭后由调用方刷新环境下拉框。
 * <p>
 * v2 适配: 环境以 EnvironmentFile 表示, 变量以 List&lt;Variable&gt; 存储,
 * 变量表格包含启用/Key/Value/Secret 四列, 不再使用 ObjectMapper 进行 JSON 序列化。
 * <p>
 * 布局:
 * - 左侧: 环境列表 JList + [New][Delete] 按钮
 * - 右侧: 环境名称输入框 + 变量表格 (启用, Key, Value, Secret) + [Save][Close] 按钮
 */
public class EnvironmentDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentDialog.class);

    private final transient EnvironmentService environmentService;

    private final DefaultListModel<EnvironmentFile> envListModel;
    private final JList<EnvironmentFile> envList;

    private final JTextField nameField;
    private final DefaultTableModel variablesTableModel;
    private JTable variablesTable;

    /** 是否在界面中显示敏感变量的真实值 (false 时敏感值掩码显示为 ******) */
    private boolean showSecretValues = false;

    /** 当前正在编辑的环境, null 表示新建未保存的环境 */
    private transient EnvironmentFile currentEnvironment;

    public EnvironmentDialog(JFrame parent, EnvironmentService environmentService) {
        super(parent, "管理环境", true);
        this.environmentService = environmentService;

        // ===== 左侧: 环境列表 =====
        envListModel = new DefaultListModel<>();
        envList = new JList<>(envListModel);
        envList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        envList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof EnvironmentFile) {
                    setText(((EnvironmentFile) value).getName());
                }
                return this;
            }
        });
        envList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            EnvironmentFile selected = envList.getSelectedValue();
            if (selected != null) {
                loadEnvironmentToEditor(selected);
            }
        });
        JScrollPane envListScroll = new JScrollPane(envList);

        JButton newButton = new JButton("新建");
        JButton deleteButton = new JButton("删除");
        newButton.addActionListener(e -> createNewEnvironment());
        deleteButton.addActionListener(e -> deleteSelectedEnvironment());

        JPanel leftButtonPanel = new JPanel(new MigLayout("insets 0", "[]push[]", ""));
        leftButtonPanel.add(newButton, "");
        leftButtonPanel.add(deleteButton, "");

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("环境列表"));
        leftPanel.add(envListScroll, BorderLayout.CENTER);
        leftPanel.add(leftButtonPanel, BorderLayout.SOUTH);

        // ===== 右侧: 编辑区 =====
        JPanel editPanel = new JPanel(new MigLayout("fill, wrap 2", "[][grow]", "[][][grow][][]"));
        editPanel.setBorder(BorderFactory.createTitledBorder("环境详情"));

        editPanel.add(new JLabel("名称:"), "");
        nameField = new JTextField();
        editPanel.add(nameField, "grow, wrap");

        editPanel.add(new JLabel("变量:"), "span, wrap");
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
        variablesTable = new JTable(variablesTableModel);
        variablesTable.getTableHeader().setReorderingAllowed(false);
        variablesTable.setRowHeight(24);
        variablesTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        variablesTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        variablesTable.getColumnModel().getColumn(2).setPreferredWidth(250);
        variablesTable.getColumnModel().getColumn(3).setPreferredWidth(60);

        // Value 列自定义渲染: 对应行 Secret=true 且隐藏模式下显示掩码 ******, 实际值保留在模型中
        variablesTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!showSecretValues) {
                    int modelRow = table.convertRowIndexToModel(row);
                    Boolean secret = (Boolean) variablesTableModel.getValueAt(modelRow, 3);
                    if (Boolean.TRUE.equals(secret)) {
                        setText("******");
                    }
                }
                return this;
            }
        });

        // 敏感列表头 tooltip 说明
        TableColumn secretColumn = variablesTable.getColumnModel().getColumn(3);
        TableCellRenderer defaultHeaderRenderer = variablesTable.getTableHeader().getDefaultRenderer();
        secretColumn.setHeaderRenderer((table, value, isSelected, hasFocus, row, column) -> {
            Component c = defaultHeaderRenderer.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (c instanceof JComponent) {
                ((JComponent) c).setToolTipText("勾选后该变量值在界面中掩码显示,防止敏感信息泄露");
            }
            return c;
        });

        JScrollPane tableScroll = new JScrollPane(variablesTable);
        editPanel.add(tableScroll, "span, grow, pushy, wrap");

        // 变量操作按钮: 添加变量 / 删除变量 / 显示或隐藏敏感值
        JButton addVarButton = new JButton("添加变量");
        JButton deleteVarButton = new JButton("删除变量");
        JButton toggleSecretButton = new JButton("显示敏感值");
        toggleSecretButton.setToolTipText("切换是否显示敏感变量的真实值");
        addVarButton.addActionListener(e -> {
            variablesTableModel.addRow(new Object[]{Boolean.TRUE, "", "", Boolean.FALSE});
            int lastRow = variablesTableModel.getRowCount() - 1;
            variablesTable.scrollRectToVisible(variablesTable.getCellRect(lastRow, 0, true));
            variablesTable.setRowSelectionInterval(lastRow, lastRow);
            variablesTable.editCellAt(lastRow, 1);
            variablesTable.transferFocus();
        });
        deleteVarButton.addActionListener(e -> deleteSelectedRows());
        toggleSecretButton.addActionListener(e -> {
            showSecretValues = !showSecretValues;
            toggleSecretButton.setText(showSecretValues ? "隐藏敏感值" : "显示敏感值");
            variablesTable.repaint();
        });
        JPanel varButtonPanel = new JPanel(new MigLayout("insets 0", "[][]push[]", ""));
        varButtonPanel.add(addVarButton, "");
        varButtonPanel.add(deleteVarButton, "");
        varButtonPanel.add(toggleSecretButton, "");
        editPanel.add(varButtonPanel, "span, grow, wrap");

        // 变量表格右键上下文菜单
        initTableContextMenu();

        JButton saveButton = new JButton("保存");
        JButton closeButton = new JButton("关闭");
        saveButton.addActionListener(e -> saveCurrentEnvironment());
        closeButton.addActionListener(e -> dispose());

        JPanel rightButtonPanel = new JPanel(new MigLayout("insets 0", "push[][]", ""));
        rightButtonPanel.add(saveButton, "");
        rightButtonPanel.add(closeButton, "");
        editPanel.add(rightButtonPanel, "span, grow");

        // ===== 主布局 =====
        setLayout(new MigLayout("fill, wrap 2", "[180][grow]", "[grow]"));
        add(leftPanel, "grow, pushy");
        add(editPanel, "grow, pushy");

        setSize(700, 480);
        setLocationRelativeTo(parent);

        loadEnvironmentList();
    }

    /**
     * 加载所有环境到列表。
     */
    private void loadEnvironmentList() {
        envListModel.clear();
        List<EnvironmentFile> environments = environmentService.getAllEnvironments();
        for (EnvironmentFile env : environments) {
            envListModel.addElement(env);
        }
        if (!envListModel.isEmpty()) {
            envList.setSelectedIndex(0);
        } else {
            clearEditor();
        }
    }

    /**
     * 将选中的环境加载到右侧编辑区。
     *
     * @param env 选中的环境
     */
    private void loadEnvironmentToEditor(EnvironmentFile env) {
        currentEnvironment = env;
        nameField.setText(env.getName() != null ? env.getName() : "");
        loadVariables(env.getVariables());
    }

    /**
     * 加载变量列表到表格。
     *
     * @param variables 变量列表
     */
    private void loadVariables(List<Variable> variables) {
        variablesTableModel.setRowCount(0);
        if (variables != null) {
            for (Variable v : variables) {
                variablesTableModel.addRow(new Object[]{
                        v.isEnabled(),
                        v.getKey() != null ? v.getKey() : "",
                        v.getValue() != null ? v.getValue() : "",
                        v.isSecret()
                });
            }
        }
    }

    /**
     * 清空右侧编辑区, 用于新建环境。
     */
    private void clearEditor() {
        currentEnvironment = null;
        nameField.setText("");
        variablesTableModel.setRowCount(0);
    }

    /**
     * 新建环境: 清空编辑区, 设置默认名称。
     * 实际创建在 Save 时执行。
     */
    private void createNewEnvironment() {
        envList.clearSelection();
        clearEditor();
        nameField.setText("新环境");
        nameField.requestFocusInWindow();
    }

    /**
     * 删除选中的环境。
     */
    private void deleteSelectedEnvironment() {
        EnvironmentFile selected = envList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "请选择要删除的环境。",
                    "未选择", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "删除环境 \"" + selected.getName() + "\"？",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            environmentService.deleteEnvironment(selected.getId());
            loadEnvironmentList();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "删除环境失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 保存当前编辑的环境。新建时调用 createEnvironment 再 updateEnvironment, 已有时直接 updateEnvironment。
     */
    private void saveCurrentEnvironment() {
        String name = nameField.getText();
        if (name == null || name.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "环境名称不能为空。",
                    "校验错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            if (currentEnvironment == null) {
                // 新建
                EnvironmentFile created = environmentService.createEnvironment(name.trim());
                created.setVariables(collectVariables());
                environmentService.updateEnvironment(created);
                loadEnvironmentList();
                selectEnvironmentById(created.getId());
            } else {
                // 更新
                currentEnvironment.setName(name.trim());
                currentEnvironment.setVariables(collectVariables());
                environmentService.updateEnvironment(currentEnvironment);
                loadEnvironmentList();
                selectEnvironmentById(currentEnvironment.getId());
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "保存环境失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 按 ID 选中环境列表中的对应项。
     *
     * @param id 环境 ID
     */
    private void selectEnvironmentById(String id) {
        for (int i = 0; i < envListModel.size(); i++) {
            EnvironmentFile env = envListModel.get(i);
            if (id.equals(env.getId())) {
                envList.setSelectedIndex(i);
                return;
            }
        }
    }

    /**
     * 从变量表格收集数据为 List&lt;Variable&gt;。
     *
     * @return 变量列表
     */
    private List<Variable> collectVariables() {
        List<Variable> result = new ArrayList<>();
        for (int i = 0; i < variablesTableModel.getRowCount(); i++) {
            Variable v = new Variable();
            v.setEnabled((Boolean) variablesTableModel.getValueAt(i, 0));
            v.setKey((String) variablesTableModel.getValueAt(i, 1));
            v.setValue((String) variablesTableModel.getValueAt(i, 2));
            v.setSecret((Boolean) variablesTableModel.getValueAt(i, 3));
            result.add(v);
        }
        return result;
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

        variablesTable.setComponentPopupMenu(popupMenu);

        // 右键时自动选中光标所在行
        variablesTable.addMouseListener(new MouseAdapter() {
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
                    int row = variablesTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        variablesTable.setRowSelectionInterval(row, row);
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
        int row = variablesTable.getSelectedRow();
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
        int row = variablesTable.getSelectedRow();
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
        int row = variablesTable.getSelectedRow();
        int insertAt;
        if (row < 0) {
            insertAt = variablesTableModel.getRowCount();
        } else {
            insertAt = above ? row : row + 1;
        }
        variablesTableModel.insertRow(insertAt, new Object[]{Boolean.TRUE, "", "", Boolean.FALSE});
        variablesTable.setRowSelectionInterval(insertAt, insertAt);
        variablesTable.editCellAt(insertAt, 1);
        variablesTable.transferFocus();
    }

    /**
     * 删除所有选中行。
     */
    private void deleteSelectedRows() {
        int[] selectedRows = variablesTable.getSelectedRows();
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
        EnvironmentDialog dialog = new EnvironmentDialog((JFrame) parent, environmentService);
        dialog.setVisible(true);
    }
}
