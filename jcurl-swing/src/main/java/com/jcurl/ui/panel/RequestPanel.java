package com.jcurl.ui.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcurl.model.AuthConfig;
import com.jcurl.model.KeyValue;
import com.jcurl.model.RequestNode;
import com.jcurl.model.dto.RequestConfig;
import com.jcurl.service.CookieService;
import com.jcurl.service.DefaultHeaderProvider;
import com.jcurl.service.VariableResolver;
import net.miginfocom.swing.MigLayout;
import org.springframework.stereotype.Component;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 请求构建面板 — 构建 HTTP 请求配置。
 * <p>
 * 标签页布局:
 * - Params: KeyValueTablePanel (与 URL 双向同步)
 * - Headers: KeyValueTablePanel (Key 列有常见 Header 下拉建议, 初始化预填空行)
 * - Body: CardLayout 按 bodyType 切换
 *   none / form-data(键值对+文件) / urlencoded(键值对) / raw(ContentType选择+文本) / binary(文件选择)
 * - Auth: authTypeCombo + CardLayout (none/basic/bearer/apikey)
 * <p>
 * Body 类型切换时自动管理 Content-Type 头 (description="Auto" 标记)。
 * Params 表格数据变化时通过 {@link #setUrlUpdater(Consumer)} 回调通知 MainFrame 更新 URL。
 */
@Component
public class RequestPanel extends JPanel {

    private static final String BODY_NONE = "none";
    private static final String BODY_FORM_DATA = "form-data";
    private static final String BODY_URLENCODED = "urlencoded";
    private static final String BODY_RAW = "raw";
    private static final String BODY_BINARY = "binary";

    private static final String AUTH_NONE = "none";
    private static final String AUTO_CT_DESC = "Auto";

    /** raw body 语言→Content-Type 映射, null 表示不自动设置 Content-Type */
    private static final String[][] RAW_CONTENT_TYPES = {
            {"JSON", "application/json"},
            {"XML", "application/xml"},
            {"Text", "text/plain"},
            {"HTML", "text/html"},
            {"JavaScript", "application/javascript"},
            {"Raw", null}
    };

    private final transient VariableResolver variableResolver;
    private final transient ObjectMapper objectMapper;
    /** 默认请求头提供者 (核心层), 计算并刷新界面展示的自动默认头 */
    private final transient DefaultHeaderProvider defaultHeaderProvider;
    /** Cookie 管理服务 (用于预览将随请求发送的 Cookie) */
    private final transient CookieService cookieService;

    private final KeyValueTablePanel paramsPanel;
    private final KeyValueTablePanel headersPanel;
    /** Cookie 预览标签 (位于 Headers 表格下方, 展示将随请求发送的 Cookie) */
    private JLabel cookiePreviewLabel;

    // Body — CardLayout
    private CardLayout bodyCardLayout;
    private JPanel bodyCardPanel;
    private JComboBox<String> bodyTypeCombo;

    // form-data body
    private final FormDataPanel formDataPanel;

    // urlencoded body
    private final KeyValueTablePanel urlencodedPanel;

    // raw body
    private JComboBox<String> rawContentTypeCombo;
    private JTextArea rawTextArea;

    // binary body
    private JTextField binaryFilePathField;
    private JLabel binaryFileInfoLabel;

    // Auth
    private JComboBox<String> authTypeCombo;
    private CardLayout authCardLayout;

    /** 自动重定向复选框 (默认勾选, 可取消以查看 301/302 原始响应) */
    private javax.swing.JCheckBox followRedirectsCheckBox;
    /** 包含 Cookies 复选框 (默认勾选, 控制是否自动附带当前集合存储中的 Cookie) */
    private javax.swing.JCheckBox includeCookiesCheckBox;
    private JPanel authCardPanel;

    // Auth 字段
    private JTextField basicUsernameField;
    private JPasswordField basicPasswordField;
    private JTextField bearerTokenField;
    private JTextField apiKeyField;
    private JTextField apiKeyValueField;
    private JComboBox<String> apiKeyInCombo;

    // Params → URL 同步
    /** MainFrame 设置的回调, 用于 params 变化时更新 URL 字段 */
    private Consumer<String> urlUpdater;
    /** 当前 URL (由 syncParamsFromUrl 保存) */
    private String currentUrl = "";
    /** 防止循环同步标志 */
    private boolean syncing = false;

    public RequestPanel(VariableResolver variableResolver, ObjectMapper objectMapper,
                        DefaultHeaderProvider defaultHeaderProvider, CookieService cookieService) {
        this.variableResolver = variableResolver;
        this.objectMapper = objectMapper;
        this.defaultHeaderProvider = defaultHeaderProvider;
        this.cookieService = cookieService;

        setLayout(new BorderLayout());

        // 创建 Params 和 Headers 面板
        paramsPanel = new KeyValueTablePanel(variableResolver);
        headersPanel = new KeyValueTablePanel(variableResolver);
        headersPanel.setKeySuggestions(KeyValueTablePanel.COMMON_HEADERS);

        // form-data 和 urlencoded 面板
        formDataPanel = new FormDataPanel(objectMapper);
        urlencodedPanel = new KeyValueTablePanel(variableResolver);

        // 初始化: Params 和 Headers 表格初始为空, 用户通过"添加行"按钮自行添加
        // (不再默认预填空行)

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Params", paramsPanel);
        tabbedPane.addTab("Headers", createHeadersPanel());
        tabbedPane.addTab("Body", createBodyPanel());
        tabbedPane.addTab("Auth", createAuthPanel());

        add(tabbedPane, BorderLayout.CENTER);

        // 设置 Params → URL 反向同步
        paramsPanel.setOnDataChanged(() -> syncParamsToUrl());

        // 所有 UI 组件创建完成后, 首次刷新自动默认头展示数据
        // (必须在 createBodyPanel/createAuthPanel 之后, 否则 rawContentTypeCombo 等仍为 null)
        refreshAutoHeaders();
    }

    // ==================== 自动默认头刷新 ====================

    /**
     * 根据当前 body 类型与认证配置, 重新计算界面展示的自动默认头并推送到 headersPanel。
     * 实际发送由引擎层 {@link DefaultHeaderProvider#mergeEffective} 合并, 此处仅用于展示。
     */
    private void refreshAutoHeaders() {
        if (defaultHeaderProvider == null) {
            return;
        }
        String bodyType = bodyTypeCombo != null ? (String) bodyTypeCombo.getSelectedItem() : BODY_NONE;
        String rawCt = getRawContentType();
        AuthConfig auth = buildAuthConfig();
        List<KeyValue> autoHeaders = defaultHeaderProvider.computeDisplayAutoHeaders(bodyType, rawCt, auth);
        headersPanel.setAutoHeaders(autoHeaders);
    }

    /**
     * 从 Auth 面板字段构建 AuthConfig (供刷新默认头与 buildConfig 复用)。
     */
    private AuthConfig buildAuthConfig() {
        AuthConfig auth = new AuthConfig();
        String authType = authTypeCombo != null ? (String) authTypeCombo.getSelectedItem() : AUTH_NONE;
        if (authType != null && !AUTH_NONE.equals(authType)) {
            auth.setType(authType);
            if ("basic".equals(authType)) {
                auth.setBasicUsername(basicUsernameField != null ? basicUsernameField.getText() : "");
                auth.setBasicPassword(basicPasswordField != null
                        ? new String(basicPasswordField.getPassword()) : "");
            } else if ("bearer".equals(authType)) {
                auth.setBearerToken(bearerTokenField != null ? bearerTokenField.getText() : "");
            } else if ("apikey".equals(authType)) {
                auth.setApiKeyName(apiKeyField != null ? apiKeyField.getText() : "");
                auth.setApiKeyValue(apiKeyValueField != null ? apiKeyValueField.getText() : "");
                auth.setApiKeyIn(apiKeyInCombo != null ? (String) apiKeyInCombo.getSelectedItem() : "header");
            }
        }
        return auth;
    }

    // ==================== Params ↔ URL 双向同步 ====================

    /**
     * 设置 URL 更新回调 — params 变化时调用, 通知 MainFrame 更新 URL 字段。
     */
    public void setUrlUpdater(Consumer<String> callback) {
        this.urlUpdater = callback;
    }

    /**
     * Params 表格数据变化时, 将 params 写回 URL 的 query string。
     */
    private void syncParamsToUrl() {
        if (syncing) {
            return;
        }
        List<KeyValue> params = paramsPanel.getData();
        // 提取 base URL (去掉 query 和 fragment)
        String baseUrl = currentUrl;
        int qIdx = baseUrl.indexOf('?');
        if (qIdx >= 0) {
            baseUrl = baseUrl.substring(0, qIdx);
        }
        int fIdx = baseUrl.indexOf('#');
        if (fIdx >= 0) {
            baseUrl = baseUrl.substring(0, fIdx);
        }

        // 构建 query string
        StringBuilder query = new StringBuilder();
        for (KeyValue kv : params) {
            if (!kv.isEnabled()) {
                continue;
            }
            if (kv.getKey() == null || kv.getKey().trim().isEmpty()) {
                continue;
            }
            if (query.length() > 0) {
                query.append("&");
            }
            query.append(urlEncode(kv.getKey()));
            String val = kv.getValue();
            if (val != null && !val.isEmpty()) {
                query.append("=").append(urlEncode(val));
            }
        }

        String newUrl = baseUrl;
        if (query.length() > 0) {
            newUrl = baseUrl + "?" + query;
        }

        syncing = true;
        try {
            if (urlUpdater != null) {
                urlUpdater.accept(newUrl);
            }
        } finally {
            syncing = false;
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 从 URL 解析 query 参数, 填充到 Params 表格。
     * 由 MainFrame 在 URL 字段变化时调用。
     */
    public void syncParamsFromUrl(String url) {
        currentUrl = url != null ? url : "";
        if (syncing) {
            return;
        }
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0 || queryIndex >= url.length() - 1) {
            // URL 没有 query, 清空 params 表格中的 URL 来源参数
            // 但保留用户手动添加的空行
            return;
        }
        String queryString = url.substring(queryIndex + 1);
        int fragIndex = queryString.indexOf('#');
        if (fragIndex >= 0) {
            queryString = queryString.substring(0, fragIndex);
        }
        if (queryString.trim().isEmpty()) {
            return;
        }

        // 获取现有 params
        List<KeyValue> existingParams = paramsPanel.getData();
        Map<String, KeyValue> paramMap = new HashMap<>();
        for (KeyValue kv : existingParams) {
            if (kv.getKey() != null && !kv.getKey().trim().isEmpty()) {
                paramMap.put(kv.getKey(), kv);
            }
        }

        // 解析 URL query 参数, 覆盖同 key 的
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            String key;
            String value;
            if (eqIndex >= 0) {
                key = urlDecode(pair.substring(0, eqIndex));
                value = urlDecode(pair.substring(eqIndex + 1));
            } else {
                key = urlDecode(pair);
                value = "";
            }
            if (!key.isEmpty()) {
                paramMap.put(key, new KeyValue(key, value, "", true));
            }
        }

        syncing = true;
        try {
            List<KeyValue> result = new ArrayList<>(paramMap.values());
            paramsPanel.setData(result);
        } finally {
            syncing = false;
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ==================== Headers 面板 ====================

    /**
     * 构建 Headers 标签页面板: 顶部放置 "包含 Cookies" 复选框, 中间为 Headers 键值表。
     * 复选框默认勾选, 控制发送请求时是否自动附带当前集合存储中匹配的 Cookie。
     */
    private JPanel createHeadersPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // 顶部工具条: "包含 Cookies" 复选框 (默认勾选)
        JPanel topBar = new JPanel(new MigLayout("insets 2 4 2 4", "[]push", ""));
        includeCookiesCheckBox = new javax.swing.JCheckBox("包含 Cookies", true);
        includeCookiesCheckBox.setToolTipText(
                "勾选时自动附带当前集合存储中匹配域名/路径的 Cookie; 取消则不自动附带 Cookie");
        topBar.add(includeCookiesCheckBox, "");
        panel.add(topBar, BorderLayout.NORTH);

        // 中间: Headers 键值表面板 (KeyValueTablePanel, 含自身的添加/删除/模式切换工具条)
        panel.add(headersPanel, BorderLayout.CENTER);

        // 底部: Cookie 预览标签 (展示将随请求发送的 Cookie, 灰色小字)
        cookiePreviewLabel = new JLabel("Cookies: 无");
        cookiePreviewLabel.setForeground(java.awt.Color.GRAY);
        cookiePreviewLabel.setFont(cookiePreviewLabel.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        cookiePreviewLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 4));
        cookiePreviewLabel.setToolTipText("将随该请求自动发送的 Cookie (按域名/路径匹配当前集合存储)");
        panel.add(cookiePreviewLabel, BorderLayout.SOUTH);

        return panel;
    }

    // ==================== Cookie 预览 ====================

    /**
     * 根据当前 URL 刷新 Cookie 预览标签。
     * <p>
     * 调用 {@link CookieService#getCookiesForUrl(String)} 获取匹配域名/路径的 Cookie,
     * 展示格式: "Cookies: N 个 (k1=v1; k2=v2)" 或 "Cookies: 无" (无匹配时)。
     * 超过 80 字符时截断并以 "..." 结尾。由 MainFrame 在 URL 变化、切换集合、
     * 请求响应完成后调用。
     *
     * @param url 当前请求 URL (可为 null 或空)
     */
    public void updateCookiePreview(String url) {
        String cookieValue = null;
        try {
            if (cookieService != null && url != null && !url.trim().isEmpty()) {
                cookieValue = cookieService.getCookiesForUrl(url);
            }
        } catch (Exception e) {
            cookieValue = null;
        }
        final String text;
        if (cookieValue == null || cookieValue.isEmpty()) {
            text = "Cookies: 无";
        } else {
            int count = cookieValue.split("; ").length;
            String full = "Cookies: " + count + " 个 (" + cookieValue + ")";
            if (full.length() > 80) {
                full = full.substring(0, 77) + "...";
            }
            text = full;
        }
        SwingUtilities.invokeLater(() -> {
            if (cookiePreviewLabel != null) {
                cookiePreviewLabel.setText(text);
            }
        });
    }

    // ==================== Body 面板 ====================

    private JPanel createBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel typePanel = new JPanel(new MigLayout("insets 2 4 2 4", "[][][]push", ""));
        typePanel.add(new JLabel("Body 类型:"), "");
        bodyTypeCombo = new JComboBox<>(new String[]{
                BODY_NONE, BODY_FORM_DATA, BODY_URLENCODED, BODY_RAW, BODY_BINARY
        });
        typePanel.add(bodyTypeCombo, "");
        // 自动重定向开关 (默认勾选)
        followRedirectsCheckBox = new javax.swing.JCheckBox("自动重定向", true);
        followRedirectsCheckBox.setToolTipText("勾选时自动跟随 301/302/303/307/308 重定向; 取消则返回原始重定向响应");
        typePanel.add(followRedirectsCheckBox, "");
        panel.add(typePanel, BorderLayout.NORTH);

        bodyCardLayout = new CardLayout();
        bodyCardPanel = new JPanel(bodyCardLayout);

        bodyCardPanel.add(createNoneBodyPanel(), BODY_NONE);
        bodyCardPanel.add(formDataPanel, BODY_FORM_DATA);
        bodyCardPanel.add(urlencodedPanel, BODY_URLENCODED);
        bodyCardPanel.add(createRawBodyPanel(), BODY_RAW);
        bodyCardPanel.add(createBinaryBodyPanel(), BODY_BINARY);

        panel.add(bodyCardPanel, BorderLayout.CENTER);

        bodyTypeCombo.addActionListener(e -> {
            String type = (String) bodyTypeCombo.getSelectedItem();
            if (type != null) {
                bodyCardLayout.show(bodyCardPanel, type);
            }
            // body 类型变化 → 刷新自动 Content-Type 默认头
            refreshAutoHeaders();
        });

        bodyCardLayout.show(bodyCardPanel, BODY_NONE);
        return panel;
    }

    private JPanel createNoneBodyPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 4", "[]", ""));
        panel.add(new JLabel("此请求没有 Body"), "");
        return panel;
    }

    private JPanel createRawBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel typePanel = new JPanel(new MigLayout("insets 2 4 2 4", "[][][grow]", ""));
        typePanel.add(new JLabel("Content-Type:"), "");
        String[] typeNames = new String[RAW_CONTENT_TYPES.length];
        for (int i = 0; i < RAW_CONTENT_TYPES.length; i++) {
            typeNames[i] = RAW_CONTENT_TYPES[i][0];
        }
        rawContentTypeCombo = new JComboBox<>(typeNames);
        typePanel.add(rawContentTypeCombo, "");
        JLabel ctLabel = new JLabel("");
        typePanel.add(ctLabel, "grow, wrap");
        panel.add(typePanel, BorderLayout.NORTH);

        rawContentTypeCombo.addActionListener(e -> {
            String ct = getRawContentType();
            ctLabel.setText(ct != null ? ct : "不设置 (由用户手动控制)");
            // raw Content-Type 变化 → 刷新自动默认头
            refreshAutoHeaders();
        });
        ctLabel.setText(getRawContentType() != null ? getRawContentType() : "不设置 (由用户手动控制)");

        rawTextArea = new JTextArea();
        rawTextArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        JScrollPane rawScrollPane = new JScrollPane(rawTextArea);
        panel.add(rawScrollPane, BorderLayout.CENTER);

        // 格式化按钮: 根据 Content-Type 格式化 rawTextArea 中的文本 (JSON/XML)
        JPanel formatPanel = new JPanel(new MigLayout("insets 2 4 2 4", "[]push", ""));
        JButton formatButton = new JButton("格式化");
        formatButton.setToolTipText("根据 Content-Type 格式化当前文本 (JSON/XML)");
        formatButton.addActionListener(e -> formatRawBody());
        formatPanel.add(formatButton, "");
        panel.add(formatPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * 根据当前 raw Content-Type 格式化 rawTextArea 中的文本。
     * - JSON: 用 ObjectMapper prettyPrint
     * - XML: 用 DocumentBuilder + Transformer
     * - 其他类型或格式化失败: 弹出提示
     */
    private void formatRawBody() {
        String text = rawTextArea.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String typeName = rawContentTypeCombo != null ? (String) rawContentTypeCombo.getSelectedItem() : null;
        String ct = getRawContentType();
        try {
            if ("JSON".equals(typeName) || (ct != null && ct.toLowerCase().contains("json"))) {
                Object parsed = objectMapper.readValue(text, Object.class);
                String formatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
                rawTextArea.setText(formatted);
            } else if ("XML".equals(typeName) || (ct != null && ct.toLowerCase().contains("xml"))) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document doc = db.parse(new InputSource(new StringReader(text)));
                TransformerFactory tf = TransformerFactory.newInstance();
                Transformer transformer = tf.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                StringWriter writer = new StringWriter();
                transformer.transform(new DOMSource(doc), new StreamResult(writer));
                rawTextArea.setText(writer.toString());
            } else {
                JOptionPane.showMessageDialog(this, "当前 Content-Type 不支持格式化 (仅支持 JSON/XML)",
                        "无法格式化", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "格式化失败: " + e.getMessage(),
                    "格式化错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JPanel createBinaryBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel filePanel = new JPanel(new MigLayout("insets 4", "[][grow][]", "[][]"));
        filePanel.add(new JLabel("文件:"), "");
        binaryFilePathField = new JTextField(30);
        filePanel.add(binaryFilePathField, "grow");

        JButton browseButton = new JButton("选择...");
        browseButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            int result = fileChooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                binaryFilePathField.setText(selectedFile.getAbsolutePath());
                updateBinaryFileInfo(selectedFile);
            }
        });
        filePanel.add(browseButton, "wrap");

        binaryFileInfoLabel = new JLabel("未选择文件");
        filePanel.add(binaryFileInfoLabel, "span, wrap");

        panel.add(filePanel, BorderLayout.NORTH);
        return panel;
    }

    private void updateBinaryFileInfo(File file) {
        if (file != null && file.exists()) {
            long sizeKB = file.length() / 1024;
            binaryFileInfoLabel.setText(file.getName() + " (" + sizeKB + " KB)");
        } else {
            binaryFileInfoLabel.setText("未选择文件");
        }
    }

    // ==================== Auth 面板 ====================

    private JPanel createAuthPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel typePanel = new JPanel(new MigLayout("insets 2 4 2 4", "[][]", ""));
        typePanel.add(new JLabel("认证类型:"), "");
        authTypeCombo = new JComboBox<>(new String[]{AUTH_NONE, "basic", "bearer", "apikey"});
        typePanel.add(authTypeCombo, "");
        panel.add(typePanel, BorderLayout.NORTH);

        authCardLayout = new CardLayout();
        authCardPanel = new JPanel(authCardLayout);

        authCardPanel.add(createNoneAuthPanel(), AUTH_NONE);
        basicUsernameField = new JTextField(20);
        basicPasswordField = new JPasswordField(20);
        authCardPanel.add(createBasicAuthPanel(), "basic");
        bearerTokenField = new JTextField(30);
        authCardPanel.add(createBearerAuthPanel(), "bearer");
        apiKeyField = new JTextField(20);
        apiKeyValueField = new JTextField(20);
        apiKeyInCombo = new JComboBox<>(new String[]{"header", "query"});
        authCardPanel.add(createApikeyAuthPanel(), "apikey");

        panel.add(authCardPanel, BorderLayout.CENTER);

        authTypeCombo.addActionListener(e -> {
            String type = (String) authTypeCombo.getSelectedItem();
            if (type != null) {
                authCardLayout.show(authCardPanel, type.toLowerCase());
            }
            // 认证类型变化 → 刷新认证派生默认头
            refreshAutoHeaders();
        });

        // 认证输入字段内容变化时也刷新默认头 (修复: 之前只在切换类型时刷新,
        // 输入 bearer token 后默认头不更新)
        DocumentListener authFieldListener = new SimpleDocumentListener(this::refreshAutoHeaders);
        bearerTokenField.getDocument().addDocumentListener(authFieldListener);
        basicUsernameField.getDocument().addDocumentListener(authFieldListener);
        basicPasswordField.getDocument().addDocumentListener(authFieldListener);
        apiKeyField.getDocument().addDocumentListener(authFieldListener);
        apiKeyValueField.getDocument().addDocumentListener(authFieldListener);
        apiKeyInCombo.addActionListener(e -> refreshAutoHeaders());

        return panel;
    }

    /**
     * 简化的 DocumentListener — 仅在文档变化时触发回调, 避免实现 3 个空方法。
     */
    private static class SimpleDocumentListener implements DocumentListener {
        private final Runnable callback;

        SimpleDocumentListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            callback.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            callback.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            callback.run();
        }
    }

    private JPanel createNoneAuthPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 4", "[]", ""));
        panel.add(new JLabel("无认证"), "");
        return panel;
    }

    private JPanel createBasicAuthPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 4", "[][grow]", "[][]"));
        panel.add(new JLabel("用户名:"), "");
        panel.add(basicUsernameField, "grow, wrap");
        panel.add(new JLabel("密码:"), "");
        panel.add(basicPasswordField, "grow, wrap");
        return panel;
    }

    private JPanel createBearerAuthPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 4", "[][grow]", "[]"));
        panel.add(new JLabel("令牌:"), "");
        panel.add(bearerTokenField, "grow, wrap");
        return panel;
    }

    private JPanel createApikeyAuthPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 4", "[][grow]", "[][][]"));
        panel.add(new JLabel("键:"), "");
        panel.add(apiKeyField, "grow, wrap");
        panel.add(new JLabel("值:"), "");
        panel.add(apiKeyValueField, "grow, wrap");
        panel.add(new JLabel("添加到:"), "");
        panel.add(apiKeyInCombo, "grow, wrap");
        return panel;
    }

    // ==================== Content-Type 自动管理 ====================

    /**
     * 获取当前 bodyType 对应的自动 Content-Type。
     * 返回 null 表示不自动设置 Content-Type (由用户手动控制)。
     */
    private String getAutoContentType(String bodyType) {
        if (bodyType == null) {
            return null;
        }
        switch (bodyType.toLowerCase()) {
            case BODY_FORM_DATA:
                return "multipart/form-data";
            case BODY_URLENCODED:
                return "application/x-www-form-urlencoded";
            case BODY_RAW:
                return getRawContentType();
            default:
                return null;
        }
    }

    /**
     * 获取 raw body 当前选中的 Content-Type 值。
     * "Raw" 选项返回 null (不自动设置)。
     * rawContentTypeCombo 为 null 时 (初始化早期) 返回 null。
     */
    private String getRawContentType() {
        if (rawContentTypeCombo == null) {
            return null;
        }
        Object selected = rawContentTypeCombo.getSelectedItem();
        if (selected == null) {
            return "application/json";
        }
        for (String[] mapping : RAW_CONTENT_TYPES) {
            if (mapping[0].equals(selected)) {
                return mapping[1];
            }
        }
        return "application/json";
    }

    // ==================== 数据加载与构建 ====================

    public void loadRequest(RequestNode node) {
        if (node == null) {
            return;
        }
        // 设置 method 和 url 由 MainFrame 处理
        // 加载 params
        paramsPanel.setData(node.getParams() != null ? new ArrayList<>(node.getParams()) : new ArrayList<>());
        // 加载 headers
        headersPanel.setData(node.getHeaders() != null ? new ArrayList<>(node.getHeaders()) : new ArrayList<>());
        // 加载 body
        String bodyType = node.getBodyType();
        if (bodyType != null) {
            bodyTypeCombo.setSelectedItem(bodyType);
            bodyCardLayout.show(bodyCardPanel, bodyType);
        }
        if (node.getBodyContent() != null) {
            rawTextArea.setText(node.getBodyContent());
        }
        // 加载 rawContentType
        if (node.getRawContentType() != null) {
            // 找到匹配的选项
            for (int i = 0; i < RAW_CONTENT_TYPES.length; i++) {
                if (RAW_CONTENT_TYPES[i][1] != null && RAW_CONTENT_TYPES[i][1].equals(node.getRawContentType())) {
                    rawContentTypeCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        // 加载 auth
        AuthConfig auth = node.getAuth();
        if (auth != null && !auth.isEmpty()) {
            authTypeCombo.setSelectedItem(auth.getType());
            authCardLayout.show(authCardPanel, auth.getType().toLowerCase());
            if ("basic".equals(auth.getType())) {
                basicUsernameField.setText(auth.getBasicUsername() != null ? auth.getBasicUsername() : "");
                basicPasswordField.setText(auth.getBasicPassword() != null ? auth.getBasicPassword() : "");
            } else if ("bearer".equals(auth.getType())) {
                bearerTokenField.setText(auth.getBearerToken() != null ? auth.getBearerToken() : "");
            } else if ("apikey".equals(auth.getType())) {
                apiKeyField.setText(auth.getApiKeyName() != null ? auth.getApiKeyName() : "");
                apiKeyValueField.setText(auth.getApiKeyValue() != null ? auth.getApiKeyValue() : "");
                apiKeyInCombo.setSelectedItem(auth.getApiKeyIn() != null ? auth.getApiKeyIn() : "header");
            }
        } else {
            authTypeCombo.setSelectedItem(AUTH_NONE);
            authCardLayout.show(authCardPanel, AUTH_NONE);
        }
        // 加载完成后刷新自动默认头 (反映新 body 类型与认证)
        refreshAutoHeaders();
    }

    public RequestConfig buildConfig() {
        RequestConfig config = new RequestConfig();
        config.setParams(paramsPanel.getData());

        // body
        String bodyType = (String) bodyTypeCombo.getSelectedItem();
        config.setBodyType(bodyType);
        if (BODY_RAW.equals(bodyType)) {
            config.setBodyContent(rawTextArea.getText());
            config.setRawContentType(getRawContentType());
        } else if (BODY_BINARY.equals(bodyType)) {
            config.setBodyContent(binaryFilePathField.getText());
        } else if (BODY_FORM_DATA.equals(bodyType)) {
            config.setBodyContent(formDataPanel.getBodyContent());
        } else if (BODY_URLENCODED.equals(bodyType)) {
            config.setBodyContent(serializeKeyValues(urlencodedPanel.getData()));
        }

        // headers: 仅用户输入的头; 自动默认头 (含 Content-Type、认证头) 由引擎层
        // DefaultHeaderProvider 在发送时合并, 用户同名头覆盖默认头。
        config.setHeaders(headersPanel.getData());

        // Auth — 复用面板字段构建逻辑
        config.setAuth(buildAuthConfig());

        // 自动重定向
        config.setFollowRedirects(followRedirectsCheckBox != null && followRedirectsCheckBox.isSelected());

        // 包含 Cookies (是否自动附带 Cookie)
        config.setIncludeCookies(includeCookiesCheckBox != null && includeCookiesCheckBox.isSelected());

        return config;
    }

    private String serializeKeyValues(List<KeyValue> kvs) {
        if (kvs == null || kvs.isEmpty()) {
            return "";
        }
        try {
            List<Map<String, String>> parts = new ArrayList<>();
            for (KeyValue kv : kvs) {
                Map<String, String> part = new HashMap<>();
                part.put("key", kv.getKey());
                part.put("value", kv.getValue() != null ? kv.getValue() : "");
                part.put("enabled", String.valueOf(kv.isEnabled()));
                if (kv.getDescription() != null && !kv.getDescription().isEmpty()) {
                    part.put("description", kv.getDescription());
                }
                parts.add(part);
            }
            return objectMapper.writeValueAsString(parts);
        } catch (Exception e) {
            return "";
        }
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            currentUrl = "";
            paramsPanel.clear();
            headersPanel.clear();
            bodyTypeCombo.setSelectedItem(BODY_NONE);
            formDataPanel.clear();
            urlencodedPanel.clear();
            rawTextArea.setText("");
            binaryFilePathField.setText("");
            binaryFileInfoLabel.setText("未选择文件");
            authTypeCombo.setSelectedItem(AUTH_NONE);
            basicUsernameField.setText("");
            basicPasswordField.setText("");
            bearerTokenField.setText("");
            apiKeyField.setText("");
            apiKeyValueField.setText("");
            apiKeyInCombo.setSelectedItem("header");
            // 清空后刷新自动默认头
            refreshAutoHeaders();
        });
    }
}
