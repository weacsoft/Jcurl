package com.jpostman.ui.panel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.miginfocom.swing.MigLayout;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * form-data 专用表格面板 — 支持文本和文件类型, 并支持表格/文本双模式切换。
 * <p>
 * 表格模式 5 列: enabled(Boolean)、Key、Value、Type(Text/File)、Description。
 * 选择 File 类型后, 可通过底部 Browse 按钮选择文件。
 * <p>
 * 文本模式格式:
 * - 每行一个键值对, 用冒号分割 (英文 : 或中文 ：)
 * - 以 # 开头的行视为禁用 (enabled=false)
 * - 空行跳过
 * - 文本模式下所有条目默认为 Text 类型; 若 value 形如路径 (/xxx、C:\xxx、D:\xxx),
 *   切换回表格时自动识别为 File 类型
 * <p>
 * 数据以 JSON 数组格式存储, 与 HttpEngineService 的 form-data 解析格式兼容:
 * {@code [{"key":"k1","value":"v1","type":"text"},{"key":"k2","value":"/path","type":"file"}]}
 */
public class FormDataPanel extends JPanel {

    private static final String[] COLUMN_NAMES = {"", "键", "值", "类型", "描述"};
    private static final Class<?>[] COLUMN_CLASSES = {Boolean.class, String.class, String.class, String.class, String.class};

    /** 视图模式标识 */
    private static final String VIEW_TABLE = "table";
    private static final String VIEW_TEXT = "text";

    private final ObjectMapper objectMapper;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea textArea;

    /** CardLayout 切换表格/文本视图 */
    private final CardLayout viewCardLayout;
    private final JPanel viewCardPanel;

    /** 表头排序器 */
    private TableRowSorter<DefaultTableModel> sorter;

    /** 当前视图模式 */
    private boolean textMode = false;

    private final JButton modeToggleButton;
    private final JButton addButton;
    private final JButton deleteButton;
    private final JButton browseButton;

    public FormDataPanel(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        tableModel = createTableModel();
        table = createTable();
        textArea = new JTextArea();
        textArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 13));

        viewCardLayout = new CardLayout();
        viewCardPanel = new JPanel(viewCardLayout);

        modeToggleButton = new JButton("切换为文本模式");
        addButton = new JButton("添加行");
        deleteButton = new JButton("删除选中行");
        browseButton = new JButton("选择文件...");

        initLayout();
        initListeners();
    }

    private DefaultTableModel createTableModel() {
        DefaultTableModel model = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return COLUMN_CLASSES[columnIndex];
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        return model;
    }

    private JTable createTable() {
        JTable t = new JTable(tableModel);
        t.getTableHeader().setReorderingAllowed(false);
        t.setRowHeight(24);
        t.getColumnModel().getColumn(0).setPreferredWidth(30);
        t.getColumnModel().getColumn(0).setMaxWidth(30);
        t.getColumnModel().getColumn(0).setMinWidth(30);
        t.getColumnModel().getColumn(1).setPreferredWidth(100);
        t.getColumnModel().getColumn(2).setPreferredWidth(200);
        t.getColumnModel().getColumn(3).setPreferredWidth(60);
        t.getColumnModel().getColumn(3).setMaxWidth(80);
        t.getColumnModel().getColumn(4).setPreferredWidth(120);

        // Type 列使用下拉编辑器
        TableColumn typeColumn = t.getColumnModel().getColumn(3);
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"文本", "文件"});
        typeColumn.setCellEditor(new DefaultCellEditor(typeCombo));

        // 表头点击排序: 点击表头切换升序/降序
        sorter = new TableRowSorter<>(tableModel);
        t.setRowSorter(sorter);
        t.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int column = t.columnAtPoint(e.getPoint());
                if (column < 0) {
                    return;
                }
                int modelColumn = t.convertColumnIndexToModel(column);
                SortOrder current = sorter.getSortKeys().isEmpty()
                        ? SortOrder.UNSORTED
                        : sorter.getSortKeys().get(0).getSortOrder();
                SortOrder next = current == SortOrder.ASCENDING ? SortOrder.DESCENDING : SortOrder.ASCENDING;
                sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(modelColumn, next)));
            }
        });

        return t;
    }

    private void initLayout() {
        setLayout(new BorderLayout());

        // ===== CENTER: CardLayout 切换表格/文本 =====
        JPanel tableView = new JPanel(new BorderLayout());
        JScrollPane tableScroll = new JScrollPane(table);
        tableView.add(tableScroll, BorderLayout.CENTER);

        JScrollPane textScroll = new JScrollPane(textArea);

        viewCardPanel.add(tableView, VIEW_TABLE);
        viewCardPanel.add(textScroll, VIEW_TEXT);
        add(viewCardPanel, BorderLayout.CENTER);

        // ===== SOUTH: 工具栏 =====
        JPanel buttonPanel = new JPanel(new MigLayout("insets 2 4 2 4", "[][][][grow]", ""));
        buttonPanel.add(addButton, "");
        buttonPanel.add(deleteButton, "");
        buttonPanel.add(browseButton, "");
        buttonPanel.add(modeToggleButton, "");
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void initListeners() {
        // 添加行
        addButton.addActionListener(e -> {
            tableModel.addRow(new Object[]{Boolean.TRUE, "", "", "文本", ""});
            int modelRow = tableModel.getRowCount() - 1;
            int viewRow = table.convertRowIndexToView(modelRow);
            if (viewRow >= 0) {
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                table.setRowSelectionInterval(viewRow, viewRow);
                table.setColumnSelectionInterval(1, 1);
                table.editCellAt(viewRow, 1);
            }
        });

        // 删除选中行
        deleteButton.addActionListener(e -> deleteSelectedRows());

        // 选择文件
        browseButton.addActionListener(e -> browseFile());

        // 模式切换
        modeToggleButton.addActionListener(e -> toggleMode());

        // 表格右键上下文菜单
        initTableContextMenu();
    }

    // ==================== 右键上下文菜单 ====================

    /**
     * 初始化表格右键上下文菜单。
     * 菜单项: 复制键/值/键值对/Header格式/整行, 在上方/下方插入行, 删除行。
     */
    private void initTableContextMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem copyKey = new JMenuItem("复制键");
        JMenuItem copyValue = new JMenuItem("复制值");
        JMenuItem copyPair = new JMenuItem("复制键值对");
        JMenuItem copyHeader = new JMenuItem("复制为 Header 格式");
        JMenuItem insertAbove = new JMenuItem("在上方插入行");
        JMenuItem insertBelow = new JMenuItem("在下方插入行");
        JMenuItem copyRow = new JMenuItem("复制整行");
        JMenuItem deleteRow = new JMenuItem("删除行");

        popupMenu.add(copyKey);
        popupMenu.add(copyValue);
        popupMenu.add(copyPair);
        popupMenu.add(copyHeader);
        popupMenu.addSeparator();
        popupMenu.add(copyRow);
        popupMenu.addSeparator();
        popupMenu.add(insertAbove);
        popupMenu.add(insertBelow);
        popupMenu.addSeparator();
        popupMenu.add(deleteRow);

        table.setComponentPopupMenu(popupMenu);

        copyKey.addActionListener(e -> copyCellToClipboard(1));   // Key 列索引 = 1
        copyValue.addActionListener(e -> copyCellToClipboard(2)); // Value 列索引 = 2
        copyPair.addActionListener(e -> copyPairToClipboard());
        copyHeader.addActionListener(e -> copyPairToClipboard()); // 与键值对格式相同, 语义用于 Header
        copyRow.addActionListener(e -> copyRowToClipboard());
        insertAbove.addActionListener(e -> insertRow(true));
        insertBelow.addActionListener(e -> insertRow(false));
        deleteRow.addActionListener(e -> deleteSelectedRows());
    }

    /**
     * 复制选中行指定列的值到系统剪贴板。
     */
    private void copyCellToClipboard(int columnIndex) {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        Object value = tableModel.getValueAt(modelRow, columnIndex);
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
        int modelRow = table.convertRowIndexToModel(row);
        String key = (String) tableModel.getValueAt(modelRow, 1);
        String value = (String) tableModel.getValueAt(modelRow, 2);
        String text = (key != null ? key : "") + ": " + (value != null ? value : "");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * 复制选中行整行 (Key\tValue\tType\tDescription, Tab 分隔) 到系统剪贴板。
     */
    private void copyRowToClipboard() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        String key = (String) tableModel.getValueAt(modelRow, 1);
        String value = (String) tableModel.getValueAt(modelRow, 2);
        String type = (String) tableModel.getValueAt(modelRow, 3);
        String description = (String) tableModel.getValueAt(modelRow, 4);
        String text = (key != null ? key : "") + "\t"
                + (value != null ? value : "") + "\t"
                + (type != null ? type : "") + "\t"
                + (description != null ? description : "");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * 在选中行上方 (above=true) 或下方 (above=false) 插入一个空行。
     * 未选中行时追加到末尾。
     */
    private void insertRow(boolean above) {
        int row = table.getSelectedRow();
        int insertAt;
        if (row < 0) {
            insertAt = tableModel.getRowCount();
        } else {
            int modelRow = table.convertRowIndexToModel(row);
            insertAt = above ? modelRow : modelRow + 1;
        }
        tableModel.insertRow(insertAt, new Object[]{Boolean.TRUE, "", "", "文本", ""});
        int viewRow = table.convertRowIndexToView(insertAt);
        if (viewRow >= 0) {
            table.setRowSelectionInterval(viewRow, viewRow);
            table.setColumnSelectionInterval(1, 1);
            table.editCellAt(viewRow, 1);
        }
    }

    /**
     * 删除所有选中行 (处理排序时的 view→model 索引转换)。
     */
    private void deleteSelectedRows() {
        int[] selectedRows = table.getSelectedRows();
        if (selectedRows == null || selectedRows.length == 0) {
            return;
        }
        int[] modelRows = new int[selectedRows.length];
        for (int i = 0; i < selectedRows.length; i++) {
            modelRows[i] = table.convertRowIndexToModel(selectedRows[i]);
        }
        Arrays.sort(modelRows);
        for (int i = modelRows.length - 1; i >= 0; i--) {
            tableModel.removeRow(modelRows[i]);
        }
    }

    // ==================== 模式切换 ====================

    /**
     * 切换表格/文本模式。
     * 切换前将当前视图的数据转换到目标视图。
     */
    private void toggleMode() {
        if (!textMode) {
            // 表格 → 文本: 将表格数据转为文本
            List<Map<String, String>> parts = extractPartsFromTable();
            textArea.setText(convertToText(parts));
            textMode = true;
            viewCardLayout.show(viewCardPanel, VIEW_TEXT);
            modeToggleButton.setText("切换为表格模式");
            addButton.setEnabled(false);
            deleteButton.setEnabled(false);
            browseButton.setEnabled(false);
        } else {
            // 文本 → 表格: 解析文本转为表格
            List<Map<String, String>> parts = parseFromText(textArea.getText());
            setDataToTable(parts);
            textMode = false;
            viewCardLayout.show(viewCardPanel, VIEW_TABLE);
            modeToggleButton.setText("切换为文本模式");
            addButton.setEnabled(true);
            deleteButton.setEnabled(true);
            browseButton.setEnabled(true);
        }
    }

    /**
     * 为选中行选择文件。
     * 仅对 Type=File 的行有效。
     */
    private void browseFile() {
        int selectedRow = table.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "请先选择一行",
                    "未选择", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = table.convertRowIndexToModel(selectedRow);
        String type = (String) tableModel.getValueAt(modelRow, 3);
        if (!"文件".equals(type)) {
            JOptionPane.showMessageDialog(this, "选择文件仅适用于文件类型的行。\n请先将类型列改为'文件'。",
                    "类型错误", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            tableModel.setValueAt(selectedFile.getAbsolutePath(), modelRow, 2);
        }
    }

    // ==================== 文本模式转换 ====================

    /**
     * 将 parts 列表转为文本格式。
     * 每行 "key: value", disabled 行以 # 开头。跳过空行。
     */
    private String convertToText(List<Map<String, String>> parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) {
            return sb.toString();
        }
        for (Map<String, String> part : parts) {
            String key = part.get("key");
            String value = part.getOrDefault("value", "");
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            String enabledStr = part.get("enabled");
            boolean enabled = enabledStr == null || "true".equalsIgnoreCase(enabledStr);
            if (!enabled) {
                sb.append("# ");
            }
            sb.append(key.trim()).append(": ").append(value != null ? value : "").append("\n");
        }
        return sb.toString();
    }

    /**
     * 从文本解析为 parts 列表。
     * 支持中英文冒号 (: 或 ：), 去掉 key/value 前后空格。
     * 以 # 开头的行视为 disabled。
     * 若 value 形如路径则自动识别为 File 类型, 否则为 Text 类型。
     */
    private List<Map<String, String>> parseFromText(String text) {
        List<Map<String, String>> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            String trimmed = line.trim();

            // 注释/禁用行
            boolean disabled = false;
            if (trimmed.startsWith("#")) {
                disabled = true;
                trimmed = trimmed.substring(1).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }

            // 查找第一个冒号 (英文或中文)
            int colonIdx = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == ':' || c == '：') {
                    colonIdx = i;
                    break;
                }
            }

            String key;
            String value;
            if (colonIdx >= 0) {
                key = trimmed.substring(0, colonIdx).trim();
                value = trimmed.substring(colonIdx + 1).trim();
            } else {
                // 没有冒号, 整行作为 key, value 为空
                key = trimmed;
                value = "";
            }

            if (key.isEmpty()) {
                continue;
            }

            String type = looksLikeFilePath(value) ? "file" : "text";
            Map<String, String> part = new HashMap<>();
            part.put("key", key);
            part.put("value", value);
            part.put("type", type);
            part.put("enabled", String.valueOf(!disabled));
            result.add(part);
        }
        return result;
    }

    /**
     * 判断 value 是否形如文件路径 (用于文本模式自动识别 File 类型)。
     * 匹配: /xxx、\xxx、C:\xxx、D:/xxx 等。
     */
    private boolean looksLikeFilePath(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.startsWith("/") || value.startsWith("\\")) {
            return true;
        }
        // Windows 盘符: C:\ D:\ C:/ 等
        if (value.length() >= 3) {
            char c0 = value.charAt(0);
            char c1 = value.charAt(1);
            char c2 = value.charAt(2);
            if (((c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z'))
                    && c1 == ':' && (c2 == '\\' || c2 == '/')) {
                return true;
            }
        }
        return false;
    }

    // ==================== 数据获取 ====================

    /**
     * 从当前视图获取数据 (表格或文本模式均适用), 序列化为 JSON 数组字符串。
     * 格式: {@code [{"key":"k1","value":"v1","type":"text"},{"key":"k2","value":"/path","type":"file"}]}
     * 空行 (key 和 value 都为空) 跳过。
     *
     * @return JSON 字符串, 无数据时返回空字符串
     */
    public String getBodyContent() {
        List<Map<String, String>> parts;
        if (textMode) {
            parts = parseFromText(textArea.getText());
        } else {
            parts = extractPartsFromTable();
        }
        return partsToJson(parts);
    }

    /**
     * 清理表格中 key 和 value 都为空的行。不添加空行 — 用户通过"添加行"按钮自行添加。
     * 文本模式下不做处理。
     */
    public void cleanEmptyRows() {
        if (textMode) {
            return;
        }
        // 从后往前移除 key 和 value 都为空的行
        for (int i = tableModel.getRowCount() - 1; i >= 0; i--) {
            String key = (String) tableModel.getValueAt(i, 1);
            String value = (String) tableModel.getValueAt(i, 2);
            boolean keyEmpty = (key == null || key.trim().isEmpty());
            boolean valueEmpty = (value == null || value.trim().isEmpty());
            if (keyEmpty && valueEmpty) {
                tableModel.removeRow(i);
            }
        }
    }

    /**
     * 从表格模型提取数据为 parts 列表 (直接遍历 tableModel, 不受排序影响)。
     * 提取前先清理空行 (key 和 value 都为空的行), 空行不会被序列化到 JSON 中。
     */
    private List<Map<String, String>> extractPartsFromTable() {
        cleanEmptyRows();
        List<Map<String, String>> parts = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean enabled = (Boolean) tableModel.getValueAt(i, 0);
            String key = (String) tableModel.getValueAt(i, 1);
            String value = (String) tableModel.getValueAt(i, 2);
            String type = (String) tableModel.getValueAt(i, 3);
            String description = (String) tableModel.getValueAt(i, 4);

            boolean keyEmpty = (key == null || key.trim().isEmpty());
            boolean valueEmpty = (value == null || value.trim().isEmpty());
            if (keyEmpty && valueEmpty) {
                continue;
            }

            Map<String, String> part = new HashMap<>();
            part.put("key", key != null ? key : "");
            part.put("value", value != null ? value : "");
            // 界面显示中文 (文本/文件), JSON 输出保持英文 (text/file) 以兼容 HttpEngineService
            part.put("type", (type == null || "文本".equals(type)) ? "text" : "file");
            if (description != null && !description.trim().isEmpty()) {
                part.put("description", description);
            }
            if (enabled != null) {
                part.put("enabled", enabled.toString());
            }
            parts.add(part);
        }
        return parts;
    }

    /**
     * 将 parts 列表序列化为 JSON 字符串。
     */
    private String partsToJson(List<Map<String, String>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 数据设置 ====================

    /**
     * 从 JSON 字符串加载数据到当前视图 (表格或文本模式均适用)。
     *
     * @param bodyContent JSON 数组字符串
     */
    public void setBodyContent(String bodyContent) {
        List<Map<String, String>> parts = jsonToParts(bodyContent);
        if (textMode) {
            textArea.setText(convertToText(parts));
        } else {
            setDataToTable(parts);
        }
    }

    /**
     * 将 parts 列表设置到表格模型。
     */
    private void setDataToTable(List<Map<String, String>> parts) {
        tableModel.setRowCount(0);
        if (parts != null) {
            for (Map<String, String> part : parts) {
                String enabledStr = part.get("enabled");
                boolean enabled = enabledStr == null || "true".equalsIgnoreCase(enabledStr);
                String type = part.get("type");
                if (type == null) {
                    type = "文本";
                } else {
                    type = "text".equalsIgnoreCase(type) ? "文本" : "文件";
                }
                tableModel.addRow(new Object[]{
                        enabled,
                        part.getOrDefault("key", ""),
                        part.getOrDefault("value", ""),
                        type,
                        part.getOrDefault("description", "")
                });
            }
        }
        // 不再默认添加空行 — 用户通过"添加行"按钮自行添加
    }

    /**
     * 将 JSON 字符串解析为 parts 列表。
     */
    private List<Map<String, String>> jsonToParts(String bodyContent) {
        List<Map<String, String>> parts = new ArrayList<>();
        if (bodyContent == null || bodyContent.trim().isEmpty()) {
            return parts;
        }
        try {
            List<Map<String, String>> parsed = objectMapper.readValue(bodyContent,
                    new TypeReference<List<Map<String, String>>>() {});
            if (parsed != null) {
                parts.addAll(parsed);
            }
        } catch (Exception e) {
            // 解析失败, 返回空列表
        }
        return parts;
    }

    /**
     * 确保表格至少有一个空行。
     */
    private void ensureEmptyRow() {
        if (textMode) {
            return;
        }
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String key = (String) tableModel.getValueAt(i, 1);
            String value = (String) tableModel.getValueAt(i, 2);
            if ((key == null || key.trim().isEmpty()) && (value == null || value.trim().isEmpty())) {
                return;
            }
        }
        tableModel.addRow(new Object[]{Boolean.TRUE, "", "", "文本", ""});
    }

    /**
     * 清空当前视图, 表格清空为 0 行 (用户通过"添加行"按钮自行添加)。
     */
    public void clear() {
        if (textMode) {
            textArea.setText("");
        } else {
            tableModel.setRowCount(0);
        }
    }
}
