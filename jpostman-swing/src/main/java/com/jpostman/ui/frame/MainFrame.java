package com.jpostman.ui.frame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.jpostman.model.CollectionFile;
import com.jpostman.model.EnvironmentFile;
import com.jpostman.model.RequestNode;
import com.jpostman.model.Settings;
import com.jpostman.model.dto.RequestConfig;
import com.jpostman.model.dto.ResponseData;
import com.jpostman.plugin.PluginManager;
import com.jpostman.service.CollectionService;
import com.jpostman.service.CookieService;
import com.jpostman.service.EnvironmentService;
import com.jpostman.service.HistoryService;
import com.jpostman.service.HttpEngineService;
import com.jpostman.service.LoadTestService;
import com.jpostman.service.VariableResolver;
import com.jpostman.service.store.SettingsStore;
import com.jpostman.ui.dialog.CookieManagerDialog;
import com.jpostman.ui.dialog.EnvironmentDialog;
import com.jpostman.ui.dialog.GlobalVariableDialog;
import com.jpostman.ui.dialog.LoadTestDialog;
import com.jpostman.ui.dialog.PluginManagerDialog;
import com.jpostman.ui.panel.RequestPanel;
import com.jpostman.ui.panel.ResponsePanel;
import com.jpostman.ui.panel.SidebarPanel;
import com.jpostman.util.CollectionExporter;
import com.jpostman.util.CurlParser;
import com.jpostman.util.PostmanImporter;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 应用主窗口 — v2 架构。
 * <p>
 * v2 变更:
 * <ul>
 *   <li>不再注入 ProjectService / RequestItemService (JPA 实体已移除)</li>
 *   <li>注入 CollectionService 用于集合/请求的持久化</li>
 *   <li>当前选中状态: {@link #currentCollectionId} + {@link #currentRequestNode}</li>
 *   <li>环境下拉使用 String 类型 ID ({@link EnvironmentFile})</li>
 *   <li>发送请求: RequestPanel.buildConfig() → VariableResolver → HttpEngineService → ResponsePanel</li>
 *   <li>取消请求: HttpEngineService.cancelCurrentRequest()</li>
 *   <li>保存请求: 将 RequestPanel.buildConfig() 同步到当前 RequestNode 并 collectionService.updateRequest()</li>
 * </ul>
 */
@Component
public class MainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    private static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};

    // ===== 注入的服务 =====
    private final transient CollectionService collectionService;
    private final transient HttpEngineService httpEngineService;
    private final transient LoadTestService loadTestService;
    private final transient EnvironmentService environmentService;
    private final transient HistoryService historyService;
    private final transient VariableResolver variableResolver;
    private final transient CookieService cookieService;
    private final transient ObjectMapper objectMapper;
    private final transient SettingsStore settingsStore;
    private final transient PluginManager pluginManager;
    private final SidebarPanel sidebarPanel;
    private final RequestPanel requestPanel;
    private final ResponsePanel responsePanel;

    // ===== 工具栏组件 =====
    private final JComboBox<String> methodCombo;
    private final JTextField urlField;
    private final JButton sendButton;
    private final JButton cancelButton;
    private final JButton saveButton;
    private final JComboBox<String> envCombo;
    private final JLabel statusLabel;
    private final JLabel cookieLabel;

    /** 视图菜单: 主题单选项 (亮色/暗色) */
    private JRadioButtonMenuItem lightThemeItem;
    private JRadioButtonMenuItem darkThemeItem;

    /** 环境下拉框项对应的 ID 列表 (与 envCombo 索引对齐, 第一项 "无环境" 为 null) */
    private final List<String> envIds = new ArrayList<>();
    /** 防止程序化设置下拉项时触发环境切换 */
    private boolean loadingEnv = false;

    /** 当前选中的请求所属集合 ID (保存请求时使用) */
    private String currentCollectionId;
    /** 当前选中的请求节点 (保存请求时使用) */
    private RequestNode currentRequestNode;

    public MainFrame(CollectionService collectionService,
                     HttpEngineService httpEngineService,
                     LoadTestService loadTestService,
                     EnvironmentService environmentService,
                     HistoryService historyService,
                     VariableResolver variableResolver,
                     CookieService cookieService,
                     ObjectMapper objectMapper,
                     SettingsStore settingsStore,
                     PluginManager pluginManager,
                     SidebarPanel sidebarPanel,
                     RequestPanel requestPanel,
                     ResponsePanel responsePanel) {
        super("JPostman - v2");
        this.collectionService = collectionService;
        this.httpEngineService = httpEngineService;
        this.loadTestService = loadTestService;
        this.environmentService = environmentService;
        this.historyService = historyService;
        this.variableResolver = variableResolver;
        this.cookieService = cookieService;
        this.objectMapper = objectMapper;
        this.settingsStore = settingsStore;
        this.pluginManager = pluginManager;
        this.sidebarPanel = sidebarPanel;
        this.requestPanel = requestPanel;
        this.responsePanel = responsePanel;

        this.methodCombo = new JComboBox<>(METHODS);
        this.methodCombo.setEditable(true);
        this.urlField = new JTextField();
        this.sendButton = new JButton("发送");
        this.cancelButton = new JButton("取消");
        this.saveButton = new JButton("保存请求");
        this.envCombo = new JComboBox<>();
        this.statusLabel = new JLabel("就绪");
        this.cookieLabel = new JLabel("Cookie: 0");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());
        setJMenuBar(createMenuBar());

        add(createToolBar(), BorderLayout.NORTH);

        // 中部: 左侧边栏 + 中间请求面板 + 右侧响应面板
        JPanel centerPanel = new JPanel(new MigLayout("insets 0, fill", "[280!,grow 0][grow][grow]", "[grow]"));
        centerPanel.add(new JScrollPane(sidebarPanel), "growy, growx");
        centerPanel.add(requestPanel, "grow, growy");
        centerPanel.add(responsePanel, "grow, growy");
        add(centerPanel, BorderLayout.CENTER);

        // 状态栏: 状态文本 + Cookie 计数
        JPanel statusBar = new JPanel(new MigLayout("insets 2 4 2 4, fill", "[grow][]"));
        statusBar.add(statusLabel, "growx");
        statusBar.add(cookieLabel, "");
        add(statusBar, BorderLayout.SOUTH);

        setupEventHandlers();
        setupSidebarCallbacks();
        setupKeyboardShortcuts();

        // 初始加载: 加载所有插件
        try {
            pluginManager.loadAll();
        } catch (Exception e) {
            log.warn("加载插件失败", e);
        }
        refreshEnvironmentCombo();
        sidebarPanel.refreshTree();
        sidebarPanel.refreshHistory();
        updateCookieStatus();

        // 视图: 同步主题菜单选中状态
        syncThemeMenu();
    }

    // ==================== UI 构建 ====================

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // 文件菜单
        JMenu fileMenu = new JMenu("文件");

        JMenuItem importCurlItem = new JMenuItem("导入 cURL...");
        importCurlItem.addActionListener(e -> importCurl());
        fileMenu.add(importCurlItem);

        JMenuItem importPostmanItem = new JMenuItem("导入 Postman Collection...");
        importPostmanItem.addActionListener(e -> importPostmanCollection());
        fileMenu.add(importPostmanItem);

        JMenuItem exportCollectionItem = new JMenuItem("导出集合...");
        exportCollectionItem.addActionListener(e -> exportCollection());
        fileMenu.add(exportCollectionItem);

        fileMenu.add(new JSeparator());

        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> dispose());
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);

        // 编辑菜单
        JMenu editMenu = new JMenu("编辑");
        JMenuItem envItem = new JMenuItem("管理环境...");
        envItem.addActionListener(e -> openEnvironmentDialog());
        editMenu.add(envItem);

        JMenuItem globalVarItem = new JMenuItem("全局变量...");
        globalVarItem.addActionListener(e -> openGlobalVariableDialog());
        editMenu.add(globalVarItem);

        JMenuItem pluginItem = new JMenuItem("插件管理...");
        pluginItem.addActionListener(e -> openPluginManagerDialog());
        editMenu.add(pluginItem);

        editMenu.add(new JSeparator());

        JMenuItem cookieItem = new JMenuItem("管理 Cookie...");
        cookieItem.addActionListener(e -> openCookieDialog());
        editMenu.add(cookieItem);

        editMenu.add(new JSeparator());

        JMenuItem clearHistoryItem = new JMenuItem("清空历史");
        clearHistoryItem.addActionListener(e -> clearHistory());
        editMenu.add(clearHistoryItem);

        menuBar.add(editMenu);

        // 工具菜单
        JMenu toolsMenu = new JMenu("工具");
        JMenuItem loadTestItem = new JMenuItem("性能测试...");
        loadTestItem.addActionListener(e -> openLoadTestDialog());
        toolsMenu.add(loadTestItem);
        menuBar.add(toolsMenu);

        // 视图菜单
        JMenu viewMenu = new JMenu("视图");

        // 主题切换 (单选)
        lightThemeItem = new JRadioButtonMenuItem("亮色主题");
        darkThemeItem = new JRadioButtonMenuItem("暗色主题");
        ButtonGroup themeGroup = new ButtonGroup();
        themeGroup.add(lightThemeItem);
        themeGroup.add(darkThemeItem);
        lightThemeItem.addActionListener(e -> switchTheme("light"));
        darkThemeItem.addActionListener(e -> switchTheme("dark"));
        viewMenu.add(lightThemeItem);
        viewMenu.add(darkThemeItem);

        viewMenu.add(new JSeparator());

        // 字体大小
        JMenuItem increaseFontItem = new JMenuItem("增大字体");
        increaseFontItem.addActionListener(e -> changeFontSize(1));
        viewMenu.add(increaseFontItem);

        JMenuItem decreaseFontItem = new JMenuItem("减小字体");
        decreaseFontItem.addActionListener(e -> changeFontSize(-1));
        viewMenu.add(decreaseFontItem);

        menuBar.add(viewMenu);

        // 帮助菜单
        JMenu helpMenu = new JMenu("帮助");
        JMenuItem variableHelpItem = new JMenuItem("变量使用说明");
        variableHelpItem.addActionListener(e -> showVariableHelp());
        helpMenu.add(variableHelpItem);

        helpMenu.add(new JSeparator());

        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
    }

    private JPanel createToolBar() {
        JPanel toolBar = new JPanel(new MigLayout("insets 2 4 2 4, fill",
                "[][][grow][][][][]push[][]", ""));
        toolBar.add(methodCombo, "wmin 80");
        toolBar.add(new JLabel("URL:"), "");
        toolBar.add(urlField, "grow, wmin 200");
        toolBar.add(sendButton, "");
        toolBar.add(cancelButton, "");
        toolBar.add(saveButton, "");
        toolBar.add(new JLabel("环境:"), "gap unrelated");
        toolBar.add(envCombo, "wmin 150");
        return toolBar;
    }

    // ==================== 事件处理 ====================

    private void setupEventHandlers() {
        // 初始无请求进行中, 取消按钮禁用
        cancelButton.setEnabled(false);

        // 发送请求
        sendButton.addActionListener(e -> sendRequest());

        // 取消请求
        cancelButton.addActionListener(e -> cancelRequest());

        // 保存请求
        saveButton.addActionListener(e -> saveCurrentRequest());

        // URL 回车发送
        urlField.addActionListener(e -> sendRequest());

        // URL 失焦时同步参数面板 (解析 query 到参数表)
        urlField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                requestPanel.syncParamsFromUrl(urlField.getText());
            }
        });

        // 请求面板修改 URL 时回填到工具栏 URL 输入框
        requestPanel.setUrlUpdater(url -> {
            if (url != null && !url.equals(urlField.getText())) {
                SwingUtilities.invokeLater(() -> urlField.setText(url));
            }
        });

        // 环境切换
        envCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (loadingEnv) {
                    return;
                }
                int index = envCombo.getSelectedIndex();
                if (index >= 0 && index < envIds.size()) {
                    String envId = envIds.get(index);
                    try {
                        environmentService.setActiveEnvironment(envId);
                        Object selected = envCombo.getSelectedItem();
                        statusLabel.setText("环境: " + (selected != null ? selected.toString() : "无环境"));
                    } catch (Exception ex) {
                        log.warn("切换环境失败", ex);
                    }
                }
            }
        });
    }

    private void setupSidebarCallbacks() {
        // 选中集合 (或集合下任意节点) 时更新 currentCollectionId, 并切换 Cookie 上下文
        sidebarPanel.setOnCollectionSelected(collection -> {
            currentCollectionId = collection != null ? collection.getId() : null;
            // Cookie 跟随项目 (集合) 走: 切换集合即切换 Cookie 上下文
            cookieService.setCurrentCollection(currentCollectionId);
            updateCookieStatus();
        });

        // 选中请求节点时加载到 RequestPanel
        sidebarPanel.setOnRequestSelected(node -> {
            currentRequestNode = node;
            methodCombo.setSelectedItem(node.getMethod());
            urlField.setText(node.getUrl() != null ? node.getUrl() : "");
            requestPanel.loadRequest(node);
            statusLabel.setText("已加载: " + node.getName());
        });

        // 选中历史记录时加载 method + url
        sidebarPanel.setOnHistorySelected(history -> {
            methodCombo.setSelectedItem(history.getMethod());
            urlField.setText(history.getUrl() != null ? history.getUrl() : "");
            statusLabel.setText("历史: " + history.getMethod() + " " + history.getUrl());
        });

        // 树结构修改后刷新环境下拉 (保持同步)
        sidebarPanel.setOnTreeModified(this::refreshEnvironmentCombo);
    }

    // ==================== 发送 / 取消请求 ====================

    private void sendRequest() {
        RequestConfig config = requestPanel.buildConfig();

        // 工具栏的 method/url 覆盖请求面板
        Object method = methodCombo.getSelectedItem();
        config.setMethod(method != null ? method.toString().trim().toUpperCase() : "GET");
        config.setUrl(urlField.getText());

        if (config.getUrl() == null || config.getUrl().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入请求 URL", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 变量替换: URL / params / headers
        try {
            config.setUrl(variableResolver.resolve(config.getUrl()));
            config.setParams(variableResolver.resolveKeyValues(config.getParams()));
            config.setHeaders(variableResolver.resolveKeyValues(config.getHeaders()));
        } catch (Exception e) {
            log.warn("变量替换失败", e);
        }

        // 应用插件请求拦截器
        try {
            config = pluginManager.applyRequestInterceptors(config);
        } catch (Exception e) {
            log.warn("应用请求拦截器失败", e);
        }

        final RequestConfig finalConfig = config;
        setSending(true);
        responsePanel.showLoading();
        statusLabel.setText("发送中: " + config.getMethod() + " " + config.getUrl());

        SwingWorker<ResponseData, Void> worker = new SwingWorker<ResponseData, Void>() {
            @Override
            protected ResponseData doInBackground() {
                return httpEngineService.execute(finalConfig);
            }

            @Override
            protected void done() {
                setSending(false);
                try {
                    ResponseData response = get();
                    // 应用插件响应处理器
                    try {
                        response = pluginManager.applyResponseProcessors(response);
                    } catch (Exception ex) {
                        log.warn("应用响应处理器失败", ex);
                    }
                    responsePanel.showResponse(response);
                    if (response.getStatusCode() == 0) {
                        statusLabel.setText("请求失败: " + response.getErrorMessage());
                    } else {
                        statusLabel.setText(String.format("完成: %d %s - %dms",
                                response.getStatusCode(),
                                response.getStatusText() != null ? response.getStatusText() : "",
                                response.getResponseTime()));
                    }
                    // 刷新历史与 Cookie 状态
                    sidebarPanel.refreshHistory();
                    updateCookieStatus();
                } catch (Exception e) {
                    responsePanel.showError("请求执行异常: " + e.getMessage());
                    statusLabel.setText("请求异常: " + e.getMessage());
                    log.error("请求执行异常", e);
                }
            }
        };
        worker.execute();
    }

    private void cancelRequest() {
        httpEngineService.cancelCurrentRequest();
        statusLabel.setText("已请求取消");
    }

    /**
     * 切换发送/取消按钮的可用状态。
     */
    private void setSending(boolean sending) {
        sendButton.setEnabled(!sending);
        cancelButton.setEnabled(sending);
    }

    // ==================== 保存请求 ====================

    private void saveCurrentRequest() {
        if (currentCollectionId == null || currentRequestNode == null) {
            statusLabel.setText("未选择请求，无法保存");
            return;
        }
        RequestConfig config = requestPanel.buildConfig();
        Object method = methodCombo.getSelectedItem();
        config.setMethod(method != null ? method.toString().trim().toUpperCase() : "GET");
        config.setUrl(urlField.getText());

        RequestNode node = currentRequestNode;
        node.setMethod(config.getMethod());
        node.setUrl(config.getUrl());
        node.setParams(config.getParams());
        node.setHeaders(config.getHeaders());
        node.setBodyType(config.getBodyType());
        node.setBodyContent(config.getBodyContent());
        node.setRawContentType(config.getRawContentType());
        node.setAuth(config.getAuth());

        try {
            collectionService.updateRequest(currentCollectionId, node);
            statusLabel.setText("已保存: " + node.getName());
            sidebarPanel.refreshTree();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "保存请求失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            log.error("保存请求失败", e);
        }
    }

    // ==================== 环境 ====================

    private void refreshEnvironmentCombo() {
        loadingEnv = true;
        try {
            envCombo.removeAllItems();
            envIds.clear();
            envCombo.addItem("无环境");
            envIds.add(null);
            List<EnvironmentFile> envs = environmentService.getAllEnvironments();
            if (envs != null) {
                for (EnvironmentFile env : envs) {
                    envCombo.addItem(env.getName());
                    envIds.add(env.getId());
                }
            }
            // 恢复当前激活环境的选中状态
            String activeId = environmentService.getActiveEnvironmentId();
            if (activeId != null) {
                int idx = envIds.indexOf(activeId);
                if (idx >= 0) {
                    envCombo.setSelectedIndex(idx);
                }
            }
        } catch (Exception e) {
            log.warn("刷新环境下拉失败", e);
        } finally {
            loadingEnv = false;
        }
    }

    // ==================== 对话框 ====================

    private void openEnvironmentDialog() {
        EnvironmentDialog dialog = new EnvironmentDialog(this, environmentService);
        dialog.setVisible(true);
        refreshEnvironmentCombo();
        statusLabel.setText("环境已更新");
    }

    private void openGlobalVariableDialog() {
        GlobalVariableDialog dialog = new GlobalVariableDialog(this, environmentService);
        dialog.setVisible(true);
        statusLabel.setText("全局变量已更新");
    }

    private void openPluginManagerDialog() {
        PluginManagerDialog dialog = new PluginManagerDialog(this, pluginManager);
        dialog.setVisible(true);
    }

    private void openLoadTestDialog() {
        LoadTestDialog dialog = new LoadTestDialog(this, loadTestService, collectionService);
        dialog.setVisible(true);
        statusLabel.setText("性能测试窗口已打开");
    }

    private void openCookieDialog() {
        // 树形 + 表格的 Cookie 增删改查对话框 (Cookie 跟随当前集合上下文)
        CookieManagerDialog dialog = new CookieManagerDialog(this, cookieService, this::updateCookieStatus);
        dialog.setVisible(true);
        updateCookieStatus();
    }

    // ==================== 历史 ====================

    private void clearHistory() {
        int confirm = JOptionPane.showConfirmDialog(this, "确定清空所有历史记录？",
                "确认", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            historyService.clearAllHistory();
            sidebarPanel.refreshHistory();
            statusLabel.setText("历史已清空");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "清空历史失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== Cookie 状态 ====================

    private void updateCookieStatus() {
        try {
            int count = 0;
            Map<String, Map<String, CookieService.CookieEntry>> all = cookieService.getAllCookies();
            if (all != null) {
                for (Map<String, CookieService.CookieEntry> domainCookies : all.values()) {
                    if (domainCookies != null) {
                        count += domainCookies.size();
                    }
                }
            }
            final int total = count;
            SwingUtilities.invokeLater(() -> cookieLabel.setText("Cookie: " + total));
        } catch (Exception e) {
            log.warn("更新 Cookie 状态失败", e);
        }
    }

    // ==================== 帮助 ====================

    private void showVariableHelp() {
        String help = "变量使用说明:\n\n"
                + "1. 环境变量: 在 \"编辑 -> 管理环境\" 中定义, 选中环境后自动生效\n"
                + "2. 全局变量: 在 \"编辑 -> 全局变量\" 中定义, 始终生效\n"
                + "3. 变量引用语法: {{variableName}}\n"
                + "4. 可在 URL、请求头、查询参数、请求体中使用变量\n\n"
                + "示例:\n"
                + "  URL: https://{{host}}/api/{{version}}/users\n"
                + "  Header: Authorization: Bearer {{token}}";
        JOptionPane.showMessageDialog(this, help, "变量使用说明", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "JPostman v2\n\nHTTP API 测试工具\n集合文件 (JSON) + 文件夹/请求树\n\n技术栈: Java 8 + Spring Boot 2.7 + OkHttp + Swing",
                "关于", JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================== 导入 / 导出 ====================

    private void importCurl() {
        String curlText = JOptionPane.showInputDialog(this, "粘贴 cURL 命令:", "导入 cURL", JOptionPane.PLAIN_MESSAGE);
        if (curlText != null && !curlText.trim().isEmpty()) {
            RequestNode request = CurlParser.parse(curlText);
            if (request != null) {
                currentRequestNode = request;
                methodCombo.setSelectedItem(request.getMethod());
                urlField.setText(request.getUrl() != null ? request.getUrl() : "");
                requestPanel.loadRequest(request);
                statusLabel.setText("已导入 cURL 请求");
            } else {
                JOptionPane.showMessageDialog(this, "无法解析 cURL 命令", "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void importPostmanCollection() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON 文件", "json"));
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String jsonText = new String(Files.readAllBytes(fileChooser.getSelectedFile().toPath()), StandardCharsets.UTF_8);
                CollectionFile collection = PostmanImporter.importCollection(jsonText, objectMapper);
                collectionService.saveCollection(collection);
                sidebarPanel.refreshTree();
                statusLabel.setText("已导入集合: " + collection.getName());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "导入失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportCollection() {
        // 弹出集合选择对话框 (简单实现: 列出所有集合名称)
        List<CollectionFile> collections = collectionService.getAllCollections();
        if (collections.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的集合", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] names = new String[collections.size()];
        for (int i = 0; i < collections.size(); i++) {
            names[i] = collections.get(i).getName();
        }
        String selected = (String) JOptionPane.showInputDialog(this, "选择要导出的集合:", "导出集合",
                JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (selected != null) {
            for (CollectionFile c : collections) {
                if (c.getName().equals(selected)) {
                    JFileChooser fileChooser = new JFileChooser();
                    fileChooser.setSelectedFile(new File(c.getName() + ".json"));
                    if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        try {
                            String json = CollectionExporter.exportToJson(c, objectMapper);
                            Files.write(fileChooser.getSelectedFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
                            statusLabel.setText("已导出: " + fileChooser.getSelectedFile().getName());
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                    break;
                }
            }
        }
    }

    // ==================== 新建请求 ====================

    /**
     * 新建请求 (在当前选中的集合下)。
     * 若未选中集合则提示用户先选择集合。
     */
    private void newRequest() {
        if (currentCollectionId == null) {
            JOptionPane.showMessageDialog(this, "请先在左侧集合树中选择一个集合",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "输入请求名称:", "新建请求",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            RequestNode node = collectionService.createRequest(currentCollectionId, null, name.trim());
            sidebarPanel.refreshTree();
            // 选中并加载新请求
            currentRequestNode = node;
            methodCombo.setSelectedItem(node.getMethod());
            urlField.setText(node.getUrl() != null ? node.getUrl() : "");
            requestPanel.loadRequest(node);
            statusLabel.setText("已新建: " + node.getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "新建请求失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            log.error("新建请求失败", e);
        }
    }

    // ==================== 快捷键 ====================

    /**
     * 注册全局快捷键 (基于 RootPane 的 InputMap/ActionMap)。
     * <ul>
     *   <li>Ctrl+Enter: 发送请求</li>
     *   <li>Ctrl+S: 保存请求</li>
     *   <li>Ctrl+N: 新建请求 (在当前集合下)</li>
     * </ul>
     */
    private void setupKeyboardShortcuts() {
        // Ctrl+Enter: 发送请求
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "sendRequest");
        getRootPane().getActionMap().put("sendRequest", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendRequest();
            }
        });

        // Ctrl+S: 保存请求
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "saveRequest");
        getRootPane().getActionMap().put("saveRequest", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveCurrentRequest();
            }
        });

        // Ctrl+N: 新建请求 (在当前集合下)
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "newRequest");
        getRootPane().getActionMap().put("newRequest", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newRequest();
            }
        });
    }

    // ==================== 视图: 主题与字体 ====================

    /**
     * 同步主题菜单选中状态 (从 Settings 读取当前主题)。
     */
    private void syncThemeMenu() {
        Settings settings = settingsStore.load();
        if ("dark".equals(settings.getTheme())) {
            darkThemeItem.setSelected(true);
        } else {
            lightThemeItem.setSelected(true);
        }
    }

    /**
     * 切换主题 (亮色/暗色) 并刷新所有 UI。
     * 切换前保存当前字体大小, 切换后重新应用 (FlatLaf.setup 会重置 look and feel defaults),
     * 并将主题与字体大小持久化到 Settings。
     *
     * @param theme "light" 或 "dark"
     */
    private void switchTheme(String theme) {
        // 保存当前字体大小 (FlatLaf.setup 会重置 look and feel defaults)
        Font currentFont = UIManager.getFont("defaultFont");
        int currentSize = currentFont != null ? currentFont.getSize() : 12;

        if ("dark".equals(theme)) {
            FlatLaf.setup(new FlatDarculaLaf());
        } else {
            FlatLaf.setup(new FlatIntelliJLaf());
        }

        // 重新应用字体大小 (保持切换前的字体大小)
        applyFontSize(currentSize);
        // 刷新所有 UI 组件 (包括菜单栏, 菜单栏位于 RootPane 内)
        SwingUtilities.updateComponentTreeUI(this);

        // 保存主题到设置 (同时持久化当前字体大小)
        Settings settings = settingsStore.load();
        settings.setTheme(theme);
        settings.setFontSize(currentSize);
        settingsStore.save(settings);

        statusLabel.setText("主题已切换: " + ("dark".equals(theme) ? "暗色" : "亮色"));
    }

    /**
     * 调节全局字体大小 (在当前基础上增减 delta)。
     * 范围限制在 8~24 之间, 修改后刷新 UI 并保存到设置。
     *
     * @param delta 字号增减量 (正数增大, 负数减小)
     */
    private void changeFontSize(int delta) {
        Font currentFont = UIManager.getFont("defaultFont");
        if (currentFont == null) {
            currentFont = new JLabel().getFont();
        }
        int newSize = Math.max(8, Math.min(24, currentFont.getSize() + delta));
        applyFontSize(newSize);
        SwingUtilities.updateComponentTreeUI(this);

        // 保存字体大小到设置 (保持主题不变)
        Settings settings = settingsStore.load();
        settings.setFontSize(newSize);
        settingsStore.save(settings);

        statusLabel.setText("字体大小: " + newSize);
    }

    /**
     * 将 FlatLaf 默认字体设置为指定大小 (保持原字体族与样式)。
     *
     * @param size 新字体大小
     */
    private void applyFontSize(int size) {
        Font currentFont = UIManager.getFont("defaultFont");
        Font newFont;
        if (currentFont != null) {
            newFont = currentFont.deriveFont((float) size);
        } else {
            newFont = new Font(Font.SANS_SERIF, Font.PLAIN, size);
        }
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        defaults.put("defaultFont", newFont);
    }

    // ==================== 启动 ====================

    /**
     * 显示主窗口 (在 EDT 中)。
     */
    public void showFrame() {
        EventQueue.invokeLater(() -> setVisible(true));
    }
}
