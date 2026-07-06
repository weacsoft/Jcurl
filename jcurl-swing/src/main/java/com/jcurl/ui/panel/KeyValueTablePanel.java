package com.jcurl.ui.panel;

import com.jcurl.model.KeyValue;
import com.jcurl.service.VariableResolver;
import net.miginfocom.swing.MigLayout;
import org.springframework.stereotype.Component;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 可复用的键值对面板 — 用于 Params、Headers、urlencoded Body 等标签页。
 * <p>
 * 支持双模式切换:
 * - 表格模式: 4 列 (enabled, Key, Value, Description), 可逐行编辑和勾选
 * - 文本模式: 纯文本编辑区, 每行 "key: value" 或 "key：value", 支持中英文冒号
 * <p>
 * 文本模式格式:
 * - 每行一个键值对, 用冒号分割 (英文 : 或中文 ：)
 * - 以 # 开头的行视为禁用 (enabled=false)
 * - 空行跳过
 * - 切换时自动去掉 key 和 value 前后空格 (方便从 Chrome 控制台复制)
 * <p>
 * 布局 (BorderLayout):
 * - CENTER: CardLayout 切换表格视图和文本视图
 * - SOUTH: 工具栏 (模式切换按钮 + 表格模式下的添加/删除按钮)
 * <p>
 * 数据变化时通过 {@link #setOnDataChanged(Runnable)} 回调通知外部。
 * 可通过 {@link #setKeySuggestions(String[])} 为 Key 列设置下拉建议。
 * 可通过 {@link #setVariableHint(boolean)} 显示变量使用提示。
 */
@Component
public class KeyValueTablePanel extends JPanel {

    private static final String[] COLUMN_NAMES = {"", "键", "值", "描述"};
    private static final Class<?>[] COLUMN_CLASSES = {Boolean.class, String.class, String.class, String.class};

    /** 视图模式标识 */
    private static final String VIEW_TABLE = "table";
    private static final String VIEW_TEXT = "text";

    /**
     * 常见 HTTP 请求头列表, 用于 Headers 标签页的 Key 列下拉建议。
     */
    public static final String[] COMMON_HEADERS = {
            "Accept", "Accept-Charset", "Accept-Encoding", "Accept-Language",
            "Authorization", "Cache-Control", "Connection", "Content-Disposition",
            "Content-Encoding", "Content-Length", "Content-Type", "Cookie",
            "Date", "Expect", "Forwarded", "From", "Host", "If-Match",
            "If-Modified-Since", "If-None-Match", "If-Unmodified-Since",
            "Max-Forwards", "Origin", "Pragma", "Proxy-Authorization", "Range",
            "Referer", "TE", "Trailer", "Transfer-Encoding", "Upgrade",
            "User-Agent", "Via", "Warning", "WWW-Authenticate"
    };

    @SuppressWarnings("unused")
    private final transient VariableResolver variableResolver;

    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea textArea;

    /** 表头排序器 */
    private TableRowSorter<DefaultTableModel> sorter;

    /** CardLayout 切换表格/文本视图 */
    private final CardLayout viewCardLayout;
    private final JPanel viewCardPanel;

    /** 当前视图模式 */
    private boolean textMode = false;

    /** 模式切换按钮 */
    private final JButton modeToggleButton;
    private final JButton addButton;
    private final JButton deleteButton;
    /** 显示/隐藏自动默认头按钮 (类似 Postman) */
    private final JButton showDefaultsButton;

    /** 当前自动默认头列表 (只读展示, 不参与 getData 输出) */
    private List<KeyValue> currentAutoHeaders = new ArrayList<>();
    /** 是否展示自动默认头 */
    private boolean showAutoHeaders = false;
    /** 表顶自动行的数量 (只读, 不可编辑) */
    private int autoRowCount = 0;

    /** 数据变化回调, 程序化设置数据时不触发 */
    private Runnable onDataChanged;

    /** 标志: 正在程序化设置数据或模式切换转换中, 跳过回调 */
    private boolean suppressCallback = false;

    public KeyValueTablePanel(VariableResolver variableResolver) {
        this.variableResolver = variableResolver;
        tableModel = createTableModel();
        table = createTable();
        textArea = new JTextArea();
        textArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 13));

        viewCardLayout = new CardLayout();
        viewCardPanel = new JPanel(viewCardLayout);

        modeToggleButton = new JButton("切换为文本模式");
        addButton = new JButton("添加行");
        deleteButton = new JButton("删除选中行");
        showDefaultsButton = new JButton("显示默认头");

        initLayout();
        initListeners();
    }

    private DefaultTableModel createTableModel() {
        return new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return COLUMN_CLASSES[columnIndex];
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                // 自动默认头行 (表顶 autoRowCount 行) 只读, 不可编辑
                if (row < autoRowCount) {
                    return false;
                }
                return true;
            }
        };
    }

    private JTable createTable() {
        JTable t = new JTable(tableModel);
        t.getTableHeader().setReorderingAllowed(false);
        t.setRowHeight(24);
        t.getColumnModel().getColumn(0).setPreferredWidth(30);
        t.getColumnModel().getColumn(0).setMaxWidth(30);
        t.getColumnModel().getColumn(0).setMinWidth(30);
        t.getColumnModel().getColumn(1).setPreferredWidth(120);
        t.getColumnModel().getColumn(2).setPreferredWidth(200);
        t.getColumnModel().getColumn(3).setPreferredWidth(150);

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

    /**
     * 为 Key 列设置下拉建议。
     */
    public void setKeySuggestions(String[] suggestions) {
        if (suggestions == null || suggestions.length == 0) {
            return;
        }
        TableColumn keyColumn = table.getColumnModel().getColumn(1);
        JComboBox<String> combo = new JComboBox<>(suggestions);
        combo.setEditable(true);
        keyColumn.setCellEditor(new DefaultCellEditor(combo));
    }

    /**
     * 设置数据变化回调。
     */
    public void setOnDataChanged(Runnable callback) {
        this.onDataChanged = callback;
    }

    private void initLayout() {
        setLayout(new BorderLayout());

        // ===== CENTER: CardLayout 切换表格/文本 =====
        // 表格视图
        JPanel tableView = new JPanel(new BorderLayout());
        JScrollPane tableScroll = new JScrollPane(table);
        tableView.add(tableScroll, BorderLayout.CENTER);

        // 文本视图
        JScrollPane textScroll = new JScrollPane(textArea);

        viewCardPanel.add(tableView, VIEW_TABLE);
        viewCardPanel.add(textScroll, VIEW_TEXT);
        add(viewCardPanel, BorderLayout.CENTER);

        // ===== SOUTH: 工具栏 =====
        JPanel toolBar = new JPanel(new MigLayout("insets 2 4 2 4", "[][][][][grow]", ""));
        toolBar.add(addButton, "");
        toolBar.add(deleteButton, "");
        toolBar.add(modeToggleButton, "");
        toolBar.add(showDefaultsButton, "");
        add(toolBar, BorderLayout.SOUTH);
    }

    /**
     * 初始化事件监听器。
     */
    private void initListeners() {
        // 表格编辑监听
        tableModel.addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                if (suppressCallback) {
                    return;
                }
                if (e.getType() == TableModelEvent.UPDATE) {
                    fireDataChanged();
                }
            }
        });

        // 文本编辑监听
        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (!suppressCallback) fireDataChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (!suppressCallback) fireDataChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (!suppressCallback) fireDataChanged();
            }
        });

        // 添加行
        addButton.addActionListener(e -> {
            tableModel.addRow(new Object[]{Boolean.TRUE, "", "", ""});
            int modelRow = tableModel.getRowCount() - 1;
            int viewRow = table.convertRowIndexToView(modelRow);
            if (viewRow >= 0) {
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                table.setRowSelectionInterval(viewRow, viewRow);
                table.setColumnSelectionInterval(1, 1);
                table.editCellAt(viewRow, 1);
            }
            fireDataChanged();
        });

        // 删除选中行
        deleteButton.addActionListener(e -> deleteSelectedRows());

        // 模式切换
        modeToggleButton.addActionListener(e -> toggleMode());

        // 显示/隐藏自动默认头
        showDefaultsButton.addActionListener(e -> {
            setShowAutoHeaders(!showAutoHeaders);
        });

        // 表格右键上下文菜单
        initTableContextMenu();
    }

    // ==================== 自动默认头管理 ====================

    /**
     * 设置自动默认头列表 (来自 DefaultHeaderProvider), 并按当前显隐状态刷新表格。
     * 自动行只读, 不参与 {@link #getData()} 输出 —— 实际发送由引擎层合并。
     *
     * @param autoHeaders 自动默认头 (可为空)
     */
    public void setAutoHeaders(List<KeyValue> autoHeaders) {
        this.currentAutoHeaders = autoHeaders != null ? new ArrayList<>(autoHeaders) : new ArrayList<>();
        if (showAutoHeaders) {
            refreshAutoHeaderRows();
        }
    }

    /**
     * 设置是否展示自动默认头。
     */
    public void setShowAutoHeaders(boolean show) {
        this.showAutoHeaders = show;
        showDefaultsButton.setText(show ? "隐藏默认头" : "显示默认头");
        refreshAutoHeaderRows();
    }

    /**
     * 刷新表顶的自动默认头行 (先移除旧的, 再按需插入新的)。
     */
    private void refreshAutoHeaderRows() {
        // 移除现有自动行
        if (autoRowCount > 0) {
            for (int i = autoRowCount - 1; i >= 0; i--) {
                tableModel.removeRow(i);
            }
            autoRowCount = 0;
        }
        if (showAutoHeaders) {
            suppressCallback = true;
            try {
                for (int i = currentAutoHeaders.size() - 1; i >= 0; i--) {
                    KeyValue h = currentAutoHeaders.get(i);
                    tableModel.insertRow(0, new Object[]{
                            h.isEnabled(),
                            h.getKey() != null ? h.getKey() : "",
                            h.getValue() != null ? h.getValue() : "",
                            h.getDescription() != null ? h.getDescription() : ""
                    });
                }
                autoRowCount = currentAutoHeaders.size();
            } finally {
                suppressCallback = false;
            }
        }
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
     * 复制选中行整行 (Key\tValue\tDescription, Tab 分隔) 到系统剪贴板。
     */
    private void copyRowToClipboard() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        String key = (String) tableModel.getValueAt(modelRow, 1);
        String value = (String) tableModel.getValueAt(modelRow, 2);
        String description = (String) tableModel.getValueAt(modelRow, 3);
        String text = (key != null ? key : "") + "\t"
                + (value != null ? value : "") + "\t"
                + (description != null ? description : "");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * 在选中行上方 (above=true) 或下方 (above=false) 插入一个空行。
     * 未选中行时追加到末尾。不会插入到自动默认头行区域内。
     */
    private void insertRow(boolean above) {
        int row = table.getSelectedRow();
        int insertAt;
        if (row < 0) {
            insertAt = tableModel.getRowCount();
        } else {
            int modelRow = table.convertRowIndexToModel(row);
            // 不允许在自动行区域内插入
            if (modelRow < autoRowCount) {
                modelRow = autoRowCount;
            }
            insertAt = above ? modelRow : modelRow + 1;
        }
        tableModel.insertRow(insertAt, new Object[]{Boolean.TRUE, "", "", ""});
        int viewRow = table.convertRowIndexToView(insertAt);
        if (viewRow >= 0) {
            table.setRowSelectionInterval(viewRow, viewRow);
            table.setColumnSelectionInterval(1, 1);
            table.editCellAt(viewRow, 1);
        }
        fireDataChanged();
    }

    /**
     * 删除所有选中行 (处理排序时的 view→model 索引转换)。
     * 自动默认头行 (model 行 < autoRowCount) 不可删除, 自动跳过。
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
            // 跳过自动默认头行
            if (modelRows[i] < autoRowCount) {
                continue;
            }
            tableModel.removeRow(modelRows[i]);
        }
        fireDataChanged();
    }

    /**
     * 切换表格/文本模式。
     * 切换前将当前视图的数据转换到目标视图。
     */
    private void toggleMode() {
        if (!textMode) {
            // 表格 → 文本: 将表格数据转为文本
            List<KeyValue> data = getDataFromTable();
            textArea.setText(convertToText(data));
            textMode = true;
            viewCardLayout.show(viewCardPanel, VIEW_TEXT);
            modeToggleButton.setText("切换为表格模式");
            addButton.setEnabled(false);
            deleteButton.setEnabled(false);
        } else {
            // 文本 → 表格: 解析文本转为表格
            List<KeyValue> data = parseFromText(textArea.getText());
            setDataToTable(data);
            textMode = false;
            viewCardLayout.show(viewCardPanel, VIEW_TABLE);
            modeToggleButton.setText("切换为文本模式");
            addButton.setEnabled(true);
            deleteButton.setEnabled(true);
        }
    }

    /**
     * 将 KeyValue 列表转为文本格式。
     * 每行 "key: value", disabled 行以 # 开头。
     */
    private String convertToText(List<KeyValue> data) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue kv : data) {
            String key = kv.getKey();
            String value = kv.getValue();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            if (!kv.isEnabled()) {
                sb.append("# ");
            }
            sb.append(key.trim());
            sb.append(": ");
            sb.append(value != null ? value : "");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从文本解析为 KeyValue 列表。
     * 支持中英文冒号 (: 或 ：), 去掉 key/value 前后空格。
     * 以 # 开头的行视为 disabled。
     */
    private List<KeyValue> parseFromText(String text) {
        List<KeyValue> result = new ArrayList<>();
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

            if (!key.isEmpty()) {
                result.add(new KeyValue(key, value, "", !disabled));
            }
        }
        return result;
    }

    private void fireDataChanged() {
        if (onDataChanged != null) {
            SwingUtilities.invokeLater(onDataChanged);
        }
    }

    // ==================== 数据获取 ====================

    /**
     * 从当前视图获取数据 (表格或文本模式均适用)。
     * 表格模式下会先清理空行 (key 和 value 都为空的行), 保留至少一个空行供输入。
     */
    public List<KeyValue> getData() {
        cleanEmptyRows();
        if (textMode) {
            return parseFromText(textArea.getText());
        }
        return getDataFromTable();
    }

    /**
     * 清理表格中 key 和 value 都为空的行 (跳过表顶自动默认头行 autoRowCount)。
     * 不添加空行 — 用户通过"添加行"按钮自行添加。文本模式下不做处理。
     * <p>
     * 在 {@link #getData()} 时自动调用, 也可在 focusLost 等场景手动调用。
     */
    public void cleanEmptyRows() {
        if (textMode) {
            return;
        }
        suppressCallback = true;
        try {
            // 从后往前移除 key 和 value 都为空的行 (跳过自动默认头行)
            for (int i = tableModel.getRowCount() - 1; i >= autoRowCount; i--) {
                String key = (String) tableModel.getValueAt(i, 1);
                String value = (String) tableModel.getValueAt(i, 2);
                boolean keyEmpty = (key == null || key.trim().isEmpty());
                boolean valueEmpty = (value == null || value.trim().isEmpty());
                if (keyEmpty && valueEmpty) {
                    tableModel.removeRow(i);
                }
            }
        } finally {
            suppressCallback = false;
        }
    }

    /**
     * 从表格模型提取数据 (跳过表顶自动默认头行)。
     */
    private List<KeyValue> getDataFromTable() {
        List<KeyValue> result = new ArrayList<>();
        for (int i = autoRowCount; i < tableModel.getRowCount(); i++) {
            Boolean enabledObj = (Boolean) tableModel.getValueAt(i, 0);
            String key = (String) tableModel.getValueAt(i, 1);
            String value = (String) tableModel.getValueAt(i, 2);
            String description = (String) tableModel.getValueAt(i, 3);

            boolean keyEmpty = (key == null || key.trim().isEmpty());
            boolean valueEmpty = (value == null || value.trim().isEmpty());
            if (keyEmpty && valueEmpty) {
                continue;
            }

            result.add(new KeyValue(
                    key != null ? key : "",
                    value != null ? value : "",
                    description != null ? description : "",
                    enabledObj != null && enabledObj
            ));
        }
        return result;
    }

    // ==================== 数据设置 ====================

    /**
     * 设置数据到当前视图。不触发回调。
     */
    public void setData(List<KeyValue> data) {
        suppressCallback = true;
        try {
            if (textMode) {
                textArea.setText(convertToText(data));
            } else {
                setDataToTable(data);
            }
        } finally {
            suppressCallback = false;
        }
    }

    /**
     * 设置数据到表格模型 (用户数据), 之后重新插入自动默认头行。
     */
    private void setDataToTable(List<KeyValue> data) {
        tableModel.setRowCount(0);
        autoRowCount = 0;
        if (data != null) {
            for (KeyValue kv : data) {
                tableModel.addRow(new Object[]{
                        kv.isEnabled(),
                        kv.getKey() != null ? kv.getKey() : "",
                        kv.getValue() != null ? kv.getValue() : "",
                        kv.getDescription() != null ? kv.getDescription() : ""
                });
            }
        }
        // 重新插入自动默认头行 (若当前展示中)
        if (showAutoHeaders) {
            for (int i = currentAutoHeaders.size() - 1; i >= 0; i--) {
                KeyValue h = currentAutoHeaders.get(i);
                tableModel.insertRow(0, new Object[]{
                        h.isEnabled(),
                        h.getKey() != null ? h.getKey() : "",
                        h.getValue() != null ? h.getValue() : "",
                        h.getDescription() != null ? h.getDescription() : ""
                });
            }
            autoRowCount = currentAutoHeaders.size();
        }
        // 不再默认添加空行 — 用户通过"添加行"按钮自行添加
    }

    /**
     * 确保表格末尾至少有指定数量的空行。
     */
    public void ensureEmptyRows(int count) {
        if (textMode) {
            return; // 文本模式不需要空行
        }
        int emptyCount = 0;
        for (int i = tableModel.getRowCount() - 1; i >= 0; i--) {
            String key = (String) tableModel.getValueAt(i, 1);
            String value = (String) tableModel.getValueAt(i, 2);
            if ((key == null || key.trim().isEmpty()) && (value == null || value.trim().isEmpty())) {
                emptyCount++;
            } else {
                break;
            }
        }
        int toAdd = count - emptyCount;
        for (int i = 0; i < toAdd; i++) {
            tableModel.addRow(new Object[]{Boolean.TRUE, "", "", ""});
        }
    }

    /**
     * 清空数据, 留空行。
     */
    public void clear() {
        SwingUtilities.invokeLater(() -> {
            suppressCallback = true;
            try {
                if (textMode) {
                    textArea.setText("");
                } else {
                    tableModel.setRowCount(0);
                }
            } finally {
                suppressCallback = false;
            }
        });
    }

    // ==================== 预设常用 Header ====================

    /**
     * 预设 6 个常用 HTTP Header, 默认 disabled (用户勾选后生效)。
     * 类似 Postman 的隐藏默认头。
     */
    public static List<KeyValue> createDefaultHeaders() {
        List<KeyValue> headers = new ArrayList<>();
        headers.add(new KeyValue("Accept", "*/*", "", false));
        headers.add(new KeyValue("Content-Type", "application/json", "", false));
        headers.add(new KeyValue("User-Agent", "Jcurl/0.1", "", false));
        headers.add(new KeyValue("Accept-Encoding", "gzip, deflate, br", "", false));
        headers.add(new KeyValue("Accept-Language", "zh-CN,zh;q=0.9", "", false));
        headers.add(new KeyValue("Cache-Control", "no-cache", "", false));
        return headers;
    }
}
