package com.jpostman.ui.dialog;

import com.jpostman.service.CookieService;
import com.jpostman.service.CookieService.CookieEntry;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cookie 管理对话框 — 树形 (域名→Cookie) + 右侧明细表格的增删改查界面。
 * <p>
 * 左侧: 域名树 (根节点 "当前集合 Cookie" → 各域名节点 → 各 Cookie 叶子节点)。
 * 右侧: 选中域名或叶子时, 在表格中展示该域名下的 Cookie 明细, 可直接编辑后保存。
 * <p>
 * 操作:
 * - 新增 Cookie: 填写右侧表格空行后点 "保存"
 * - 编辑: 直接在表格中修改, 点 "保存"
 * - 删除: 选中行点 "删除选中"
 * - 清空域名: 选中域名节点点 "删除域名"
 * - 清空全部: "清除全部" 按钮
 */
public class CookieManagerDialog extends JDialog {

    private static final String[] COLUMN_NAMES = {"Name", "Value", "Path", "Expiry", "Secure", "HttpOnly"};
    private static final Class<?>[] COLUMN_CLASSES = {String.class, String.class, String.class,
            String.class, Boolean.class, Boolean.class};

    private final CookieService cookieService;
    private final Runnable onModified;

    private JTree domainTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode root;
    private JTable cookieTable;
    private DefaultTableModel tableModel;

    /** 当前选中的域名 (null 表示未选中具体域名) */
    private String selectedDomain;

    private final SimpleDateFormat expiryFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public CookieManagerDialog(Frame owner, CookieService cookieService, Runnable onModified) {
        super(owner, "管理 Cookie", true);
        this.cookieService = cookieService;
        this.onModified = onModified;
        setSize(900, 560);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(4, 4));

        add(createLeftPanel(), BorderLayout.WEST);
        add(createRightPanel(), BorderLayout.CENTER);
        add(createButtonPanel(), BorderLayout.SOUTH);

        refreshTree();
    }

    // ==================== 左侧域名树 ====================

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new java.awt.Dimension(260, 0));

        String scope = cookieService.getCurrentCollectionId();
        root = new DefaultMutableTreeNode("Cookie (" + scope + ")");
        treeModel = new DefaultTreeModel(root);
        domainTree = new JTree(treeModel);
        domainTree.setRootVisible(true);
        domainTree.setShowsRootHandles(true);

        domainTree.addTreeSelectionListener((TreeSelectionEvent e) -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) domainTree.getLastSelectedPathComponent();
            if (node == null || node == root) {
                selectedDomain = null;
                loadCookiesIntoTable(null);
            } else if (node.getUserObject() instanceof DomainNode) {
                selectedDomain = ((DomainNode) node.getUserObject()).domain;
                loadCookiesIntoTable(selectedDomain);
            } else if (node.getUserObject() instanceof CookieNode) {
                // 选中叶子: 定位到其所属域名
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
                if (parent != null && parent.getUserObject() instanceof DomainNode) {
                    selectedDomain = ((DomainNode) parent.getUserObject()).domain;
                    loadCookiesIntoTable(selectedDomain);
                }
            }
        });

        domainTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showTreeContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showTreeContextMenu(e);
            }
        });

        panel.add(new JLabel("域名"), BorderLayout.NORTH);
        panel.add(new JScrollPane(domainTree), BorderLayout.CENTER);
        return panel;
    }

    private void showTreeContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        TreePath path = domainTree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        domainTree.setSelectionPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        JPopupMenu popup = new JPopupMenu();

        if (node != root && node.getUserObject() instanceof DomainNode) {
            JMenuItem deleteDomain = new JMenuItem("删除该域名所有 Cookie");
            deleteDomain.addActionListener(ev -> {
                String domain = ((DomainNode) node.getUserObject()).domain;
                int confirm = JOptionPane.showConfirmDialog(this,
                        "确定删除域名 " + domain + " 下的所有 Cookie?", "确认", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    cookieService.deleteDomain(domain);
                    notifyModified();
                    refreshTree();
                }
            });
            popup.add(deleteDomain);
        }
        if (popup.getComponentCount() > 0) {
            popup.show(domainTree, e.getX(), e.getY());
        }
    }

    // ==================== 右侧明细表格 ====================

    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return COLUMN_CLASSES[columnIndex];
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        cookieTable = new JTable(tableModel);
        cookieTable.setAutoCreateRowSorter(true);
        cookieTable.setRowHeight(24);

        panel.add(new JLabel("Cookie 明细 (可直接编辑后点保存)"), BorderLayout.NORTH);
        panel.add(new JScrollPane(cookieTable), BorderLayout.CENTER);

        JPanel editBar = new JPanel(new MigLayout("insets 2 4 2 4", "[][][][grow]", ""));
        JButton addRow = new JButton("新增行");
        JButton deleteRow = new JButton("删除选中行");
        JButton saveBtn = new JButton("保存修改");
        editBar.add(addRow, "");
        editBar.add(deleteRow, "");
        editBar.add(saveBtn, "");
        JLabel hint = new JLabel(" ");
        editBar.add(hint, "grow, wrap");

        addRow.addActionListener(e -> {
            tableModel.addRow(new Object[]{"", "", "/", "", Boolean.FALSE, Boolean.FALSE});
        });
        deleteRow.addActionListener(e -> deleteSelectedTableRows());
        saveBtn.addActionListener(e -> {
            if (saveTableEdits()) {
                hint.setText("已保存");
                notifyModified();
                refreshTree();
            } else {
                hint.setText("保存失败, 请检查 Name/Domain 非空");
            }
        });

        panel.add(editBar, BorderLayout.SOUTH);
        return panel;
    }

    // ==================== 数据加载 ====================

    private void refreshTree() {
        root.removeAllChildren();
        Map<String, Map<String, CookieEntry>> all = cookieService.getAllCookies();
        for (Map.Entry<String, Map<String, CookieEntry>> entry : all.entrySet()) {
            DefaultMutableTreeNode domainNode = new DefaultMutableTreeNode(new DomainNode(entry.getKey()));
            for (CookieEntry cookie : entry.getValue().values()) {
                domainNode.add(new DefaultMutableTreeNode(new CookieNode(cookie)));
            }
            root.add(domainNode);
        }
        treeModel.reload();
        // 保持当前域名选中
        if (selectedDomain != null) {
            selectDomainInTree(selectedDomain);
        }
        loadCookiesIntoTable(selectedDomain);
    }

    private void selectDomainInTree(String domain) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof DomainNode) {
                if (((DomainNode) child.getUserObject()).domain.equals(domain)) {
                    domainTree.setSelectionPath(new TreePath(child.getPath()));
                    return;
                }
            }
        }
    }

    /**
     * 将指定域名的 Cookie 加载到右侧表格 (domain=null 时加载全部)。
     */
    private void loadCookiesIntoTable(String domain) {
        tableModel.setRowCount(0);
        Map<String, Map<String, CookieEntry>> all = cookieService.getAllCookies();
        if (domain == null) {
            // 全部
            for (Map<String, CookieEntry> domainMap : all.values()) {
                for (CookieEntry c : domainMap.values()) {
                    tableModel.addRow(toRow(c, c.getDomain()));
                }
            }
        } else {
            Map<String, CookieEntry> domainMap = all.get(domain);
            if (domainMap != null) {
                for (CookieEntry c : domainMap.values()) {
                    tableModel.addRow(toRow(c, domain));
                }
            }
        }
    }

    private Object[] toRow(CookieEntry c, String domain) {
        String expiry = c.getExpiry() > 0
                ? expiryFormat.format(new Date(c.getExpiry())) : "Session";
        return new Object[]{c.getName(), c.getValue(), c.getPath(), expiry,
                c.isSecure(), c.isHttpOnly(), domain};
    }

    // ==================== 保存编辑 ====================

    /**
     * 将表格中的编辑结果写回 CookieService。
     * 策略: 先清空当前选中域名的所有 Cookie, 再逐行重新写入 (简化更新逻辑)。
     * domain=null 时, 按每行第 7 列 (隐藏的 domain) 分组写入全部。
     *
     * @return true 表示保存成功
     */
    private boolean saveTableEdits() {
        // 收集表格数据
        List<CookieEntry> entries = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String name = (String) tableModel.getValueAt(i, 0);
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String value = (String) tableModel.getValueAt(i, 1);
            String path = (String) tableModel.getValueAt(i, 2);
            String expiryStr = (String) tableModel.getValueAt(i, 3);
            Boolean secure = (Boolean) tableModel.getValueAt(i, 4);
            Boolean httpOnly = (Boolean) tableModel.getValueAt(i, 5);

            CookieEntry entry = new CookieEntry();
            entry.setName(name.trim());
            entry.setValue(value != null ? value : "");
            entry.setPath((path == null || path.trim().isEmpty()) ? "/" : path.trim());
            entry.setExpiry(parseExpiryInput(expiryStr));
            entry.setSecure(secure != null && secure);
            entry.setHttpOnly(httpOnly != null && httpOnly);

            // domain: 单域名模式用 selectedDomain; 全部模式需要额外列, 此处回退到 selectedDomain
            if (selectedDomain != null) {
                entry.setDomain(selectedDomain);
            } else {
                // 全部模式下尝试从第 7 列取 domain
                if (tableModel.getColumnCount() > 6) {
                    Object d = tableModel.getValueAt(i, 6);
                    entry.setDomain(d != null ? d.toString() : "");
                }
            }
            if (entry.getDomain() == null || entry.getDomain().trim().isEmpty()) {
                return false;
            }
            entries.add(entry);
        }

        if (selectedDomain != null) {
            // 单域名模式: 清空该域名后重写
            cookieService.deleteDomain(selectedDomain);
            for (CookieEntry e : entries) {
                cookieService.addCookie(e);
            }
        } else {
            // 全部模式: 清空全部后重写
            cookieService.clearAll();
            for (CookieEntry e : entries) {
                cookieService.addCookie(e);
            }
        }
        return true;
    }

    /**
     * 解析用户输入的过期时间, "Session" 或空表示 session cookie。
     */
    private long parseExpiryInput(String str) {
        if (str == null || str.trim().isEmpty() || "Session".equalsIgnoreCase(str.trim())) {
            return 0;
        }
        try {
            return expiryFormat.parse(str.trim()).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private void deleteSelectedTableRows() {
        int[] rows = cookieTable.getSelectedRows();
        if (rows == null || rows.length == 0) {
            return;
        }
        // 先保存当前编辑, 再删除选中行
        // 转为 model 索引并倒序删除
        java.util.List<Integer> modelRows = new ArrayList<>();
        for (int r : rows) {
            modelRows.add(cookieTable.convertRowIndexToModel(r));
        }
        modelRows.sort(java.util.Collections.reverseOrder());
        for (int mr : modelRows) {
            tableModel.removeRow(mr);
        }
    }

    // ==================== 底部按钮 ====================

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 4", "[][][grow][]", ""));

        JButton addDomainBtn = new JButton("新增域名");
        addDomainBtn.addActionListener(e -> {
            String domain = JOptionPane.showInputDialog(this, "输入域名:", "新增域名", JOptionPane.PLAIN_MESSAGE);
            if (domain != null && !domain.trim().isEmpty()) {
                selectedDomain = domain.trim();
                refreshTree();
                loadCookiesIntoTable(selectedDomain);
            }
        });

        JButton clearAllBtn = new JButton("清除全部");
        clearAllBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this, "确定清除当前集合的所有 Cookie?",
                    "确认", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                cookieService.clearAll();
                notifyModified();
                refreshTree();
            }
        });

        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dispose());

        panel.add(addDomainBtn, "");
        panel.add(clearAllBtn, "");
        panel.add(new JLabel(" "), "grow");
        panel.add(closeBtn, "");
        return panel;
    }

    private void notifyModified() {
        if (onModified != null) {
            SwingUtilities.invokeLater(onModified);
        }
    }

    // ==================== 树节点包装对象 ====================

    private static class DomainNode {
        final String domain;

        DomainNode(String domain) {
            this.domain = domain;
        }

        @Override
        public String toString() {
            return domain;
        }
    }

    private static class CookieNode {
        final CookieEntry cookie;

        CookieNode(CookieEntry cookie) {
            this.cookie = cookie;
        }

        @Override
        public String toString() {
            return cookie.getName() + "=" + cookie.getValue();
        }
    }
}
