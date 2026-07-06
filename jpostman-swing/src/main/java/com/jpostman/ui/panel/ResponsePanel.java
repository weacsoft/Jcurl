package com.jpostman.ui.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpostman.model.dto.ResponseData;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.Map;

/**
 * 响应显示面板 — 展示请求执行后的响应数据。
 * <p>
 * 顶部状态栏: Status / Time / Size + 性能指标面板 (DNS / TCP / TTFB / 协议)。
 * 中间标签页:
 * - 响应体: 支持格式选择 (自动检测/JSON/XML/HTML/纯文本), JSON 与 XML 自动美化, HTML 渲染预览, 图片预览。
 * - 响应头: 支持表格模式与文本模式切换, 表格支持表头排序和右键复制 (名称/值/键值对)。
 * - Cookie: 解析 Set-Cookie 响应头并展示。
 * <p>
 * 所有公开方法通过 SwingUtilities.invokeLater 确保 EDT 安全。
 */
@Component
public class ResponsePanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(ResponsePanel.class);

    /** 响应体视图标识 (CardLayout 卡片名) */
    private static final String VIEW_TEXT = "text";
    private static final String VIEW_JSON = "json";
    private static final String VIEW_XML = "xml";
    private static final String VIEW_HTML = "html";
    private static final String VIEW_HTML_SOURCE = "html_source";
    private static final String VIEW_IMAGE = "image";

    /** 响应头视图标识 (CardLayout 卡片名) */
    private static final String VIEW_HDR_TABLE = "hdr_table";
    private static final String VIEW_HDR_TEXT = "hdr_text";

    /** 格式选择下拉框选项 */
    private static final String FMT_AUTO = "自动检测";
    private static final String FMT_JSON = "JSON";
    private static final String FMT_XML = "XML";
    private static final String FMT_HTML = "HTML";
    private static final String FMT_PLAIN = "纯文本";

    private final transient ObjectMapper objectMapper;

    private final JLabel statusLabel;
    private final JLabel timeLabel;
    private final JLabel sizeLabel;

    // ===== 性能指标 =====
    private final JLabel dnsLabel;
    private final JLabel tcpLabel;
    private final JLabel ttfbLabel;
    private final JLabel protocolLabel;

    // ===== 响应体组件 =====
    private final JComboBox<String> bodyFormatCombo;
    private final CardLayout bodyCardLayout;
    private final JPanel bodyCardPanel;
    private final JTextArea bodyTextArea;   // 纯文本视图
    private final JTextArea jsonTextArea;   // JSON 视图 (美化后)
    private final JTextArea xmlTextArea;    // XML 视图 (美化后)
    private final JEditorPane htmlPane;     // HTML 渲染视图
    private final JTextArea htmlSourceArea; // HTML 源码视图 (服务器环境下渲染受限时查看源码)
    private final JLabel imageLabel;        // 图片预览视图
    /** HTML 视图是否显示源码 (true=源码, false=渲染预览) */
    private boolean htmlShowSource = false;

    /** 当前响应体显示的视图 */
    private String currentBodyView = VIEW_TEXT;

    // ===== 响应头组件 =====
    private final DefaultTableModel headersTableModel;
    private final JTable headersTable;
    private final CardLayout headersCardLayout;
    private final JPanel headersCardPanel;
    private final JTextArea headersTextArea;
    private final JButton headersModeToggleButton;
    private boolean headersTextMode = false;

    // ===== Cookie 组件 =====
    private final DefaultTableModel cookiesTableModel;

    /** 当前响应体原文, 供渲染和复制使用 */
    private String currentBody = "";
    /** 当前响应头, 供文本模式转换和 Content-Type 检测使用 */
    private transient Map<String, String> currentHeaders;
    /** 当前响应数据, 供图片预览等使用 */
    private transient ResponseData currentResponse;

    /** 标志: 程序化设置格式下拉框时抑制其事件, 避免重复渲染 */
    private boolean suppressFormatListener = false;

    public ResponsePanel(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        setLayout(new BorderLayout());

        // ===== 顶部状态信息栏 + 性能指标 =====
        JPanel northPanel = new JPanel(new BorderLayout());

        JPanel statusPanel = new JPanel(new MigLayout("insets 2 4 2 4", "[][]push[][]push[]", ""));
        statusPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "响应",
                TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION));

        statusLabel = new JLabel("状态: --");
        timeLabel = new JLabel("耗时: --");
        sizeLabel = new JLabel("大小: --");

        statusPanel.add(statusLabel, "");
        statusPanel.add(timeLabel, "");
        statusPanel.add(sizeLabel, "");
        northPanel.add(statusPanel, BorderLayout.NORTH);

        // 性能指标面板
        JPanel metricsPanel = new JPanel(new MigLayout("insets 2 4 2 4", "[]push[]push[]push[]", ""));
        metricsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "性能指标",
                TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION));

        dnsLabel = new JLabel("DNS: --");
        tcpLabel = new JLabel("TCP: --");
        ttfbLabel = new JLabel("TTFB: --");
        protocolLabel = new JLabel("协议: --");

        metricsPanel.add(dnsLabel, "");
        metricsPanel.add(tcpLabel, "");
        metricsPanel.add(ttfbLabel, "");
        metricsPanel.add(protocolLabel, "");
        northPanel.add(metricsPanel, BorderLayout.SOUTH);

        add(northPanel, BorderLayout.NORTH);

        // ===== 中央标签页 =====
        JTabbedPane tabbedPane = new JTabbedPane();

        // ----- 响应体标签页 -----
        JPanel bodyPanel = new JPanel(new BorderLayout());

        bodyCardLayout = new CardLayout();
        bodyCardPanel = new JPanel(bodyCardLayout);

        bodyTextArea = createMonospacedArea();
        jsonTextArea = createMonospacedArea();
        xmlTextArea = createMonospacedArea();

        bodyCardPanel.add(new JScrollPane(bodyTextArea), VIEW_TEXT);
        bodyCardPanel.add(new JScrollPane(jsonTextArea), VIEW_JSON);
        bodyCardPanel.add(new JScrollPane(xmlTextArea), VIEW_XML);

        htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        bodyCardPanel.add(new JScrollPane(htmlPane), VIEW_HTML);

        // HTML 源码视图 (服务器环境 JEditorPane 渲染受限时, 可切换查看源码)
        htmlSourceArea = createMonospacedArea();
        bodyCardPanel.add(new JScrollPane(htmlSourceArea), VIEW_HTML_SOURCE);

        // 图片预览视图
        imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        bodyCardPanel.add(new JScrollPane(imageLabel), VIEW_IMAGE);

        bodyCardLayout.show(bodyCardPanel, VIEW_TEXT);
        bodyPanel.add(bodyCardPanel, BorderLayout.CENTER);

        // 格式选择 + 复制按钮 + 浏览器打开 + 源码切换
        JPanel bodyToolPanel = new JPanel(new MigLayout("insets 2 4 2 4", "[][][][]push[]", ""));
        bodyToolPanel.add(new JLabel("格式:"), "");
        bodyFormatCombo = new JComboBox<>(
                new String[]{FMT_AUTO, FMT_JSON, FMT_XML, FMT_HTML, FMT_PLAIN});
        bodyToolPanel.add(bodyFormatCombo, "");
        JButton copyButton = new JButton("复制");
        copyButton.addActionListener(e -> copyBodyToClipboard());
        bodyToolPanel.add(copyButton, "");

        // 在系统浏览器中打开 HTML 预览 (服务器环境 JEditorPane 渲染受限时的可靠方案)
        JButton openInBrowserButton = new JButton("在浏览器中打开");
        openInBrowserButton.setToolTipText("用系统默认浏览器打开当前响应内容");
        openInBrowserButton.addActionListener(e -> openInBrowser());
        bodyToolPanel.add(openInBrowserButton, "");

        // HTML 源码 / 预览切换
        JButton htmlSourceToggle = new JButton("查看源码");
        htmlSourceToggle.setToolTipText("在 HTML 渲染预览与源码之间切换");
        htmlSourceToggle.addActionListener(e -> {
            htmlShowSource = !htmlShowSource;
            htmlSourceToggle.setText(htmlShowSource ? "查看预览" : "查看源码");
            // 仅当当前为 HTML 视图时立即切换
            if (VIEW_HTML.equals(currentBodyView) || VIEW_HTML_SOURCE.equals(currentBodyView)) {
                applyBodyFormat(FMT_HTML);
            }
        });
        bodyToolPanel.add(htmlSourceToggle, "");
        bodyPanel.add(bodyToolPanel, BorderLayout.NORTH);

        bodyFormatCombo.addActionListener(e -> {
            if (suppressFormatListener) {
                return;
            }
            applyBodyFormat((String) bodyFormatCombo.getSelectedItem());
        });

        tabbedPane.addTab("响应体", bodyPanel);

        // ----- 响应头标签页 -----
        headersTableModel = new DefaultTableModel(new String[]{"名称", "值"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        headersTable = new JTable(headersTableModel);
        headersTable.getTableHeader().setReorderingAllowed(false);
        headersTable.setRowHeight(24);
        headersTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        headersTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        // 表头排序
        TableRowSorter<DefaultTableModel> headersSorter = new TableRowSorter<>(headersTableModel);
        headersTable.setRowSorter(headersSorter);
        // 右键菜单
        initHeadersContextMenu();

        headersCardLayout = new CardLayout();
        headersCardPanel = new JPanel(headersCardLayout);
        headersCardPanel.add(new JScrollPane(headersTable), VIEW_HDR_TABLE);
        headersTextArea = createMonospacedArea();
        headersCardPanel.add(new JScrollPane(headersTextArea), VIEW_HDR_TEXT);
        headersCardLayout.show(headersCardPanel, VIEW_HDR_TABLE);

        headersModeToggleButton = new JButton("切换为文本模式");
        headersModeToggleButton.addActionListener(e -> toggleHeadersMode());

        JPanel headersPanel = new JPanel(new BorderLayout());
        headersPanel.add(headersCardPanel, BorderLayout.CENTER);
        JPanel headersToolPanel = new JPanel(new MigLayout("insets 2 4 2 4", "push[]", ""));
        headersToolPanel.add(headersModeToggleButton, "");
        headersPanel.add(headersToolPanel, BorderLayout.SOUTH);

        tabbedPane.addTab("响应头", headersPanel);

        // ----- Cookie 标签页 -----
        cookiesTableModel = new DefaultTableModel(new String[]{"名称", "值", "路径", "域名", "过期"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable cookiesTable = new JTable(cookiesTableModel);
        cookiesTable.getTableHeader().setReorderingAllowed(false);
        cookiesTable.setRowHeight(24);
        cookiesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        cookiesTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        cookiesTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        cookiesTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        cookiesTable.getColumnModel().getColumn(4).setPreferredWidth(180);

        JScrollPane cookiesScroll = new JScrollPane(cookiesTable);
        cookiesScroll.setBorder(BorderFactory.createTitledBorder("响应 Cookie"));
        tabbedPane.addTab("Cookie", cookiesScroll);

        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * 创建只读等宽字体文本区。
     */
    private JTextArea createMonospacedArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        return area;
    }

    /**
     * 初始化响应头表格的右键菜单: 复制名称 / 复制值 / 复制键值对。
     */
    private void initHeadersContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem copyNameItem = new JMenuItem("复制名称");
        JMenuItem copyValueItem = new JMenuItem("复制值");
        JMenuItem copyPairItem = new JMenuItem("复制键值对");
        copyNameItem.addActionListener(e -> copyHeaderCell(0));
        copyValueItem.addActionListener(e -> copyHeaderCell(1));
        copyPairItem.addActionListener(e -> copyHeaderPair());
        popup.add(copyNameItem);
        popup.add(copyValueItem);
        popup.add(copyPairItem);
        headersTable.setComponentPopupMenu(popup);

        // 右键时自动选中光标所在行, 方便后续复制
        headersTable.addMouseListener(new MouseAdapter() {
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
                int row = headersTable.rowAtPoint(e.getPoint());
                if (row >= 0 && !headersTable.isRowSelected(row)) {
                    headersTable.setRowSelectionInterval(row, row);
                }
            }
        });
    }

    /**
     * 复制选中行的指定列 (0=名称, 1=值) 到系统剪贴板。
     */
    private void copyHeaderCell(int column) {
        int viewRow = headersTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int modelRow = headersTable.convertRowIndexToModel(viewRow);
        Object value = headersTableModel.getValueAt(modelRow, column);
        String text = value != null ? value.toString() : "";
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    /**
     * 复制选中行的 "Name: Value" 到系统剪贴板。
     */
    private void copyHeaderPair() {
        int viewRow = headersTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int modelRow = headersTable.convertRowIndexToModel(viewRow);
        Object nameObj = headersTableModel.getValueAt(modelRow, 0);
        Object valueObj = headersTableModel.getValueAt(modelRow, 1);
        String name = nameObj != null ? nameObj.toString() : "";
        String value = valueObj != null ? valueObj.toString() : "";
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(name + ": " + value), null);
    }

    /**
     * 显示响应数据。
     * 状态栏显示状态码/耗时/大小, 性能指标显示 DNS/TCP/TTFB/协议,
     * 响应体按所选格式渲染 (图片类型自动预览), 响应头填充表格, Cookie 解析 Set-Cookie。
     *
     * @param response 响应数据
     */
    public void showResponse(ResponseData response) {
        SwingUtilities.invokeLater(() -> {
            if (response == null) {
                return;
            }

            currentResponse = response;

            String statusText;
            if (response.getStatusCode() > 0) {
                statusText = response.getStatusCode() + " " +
                        (response.getStatusText() != null ? response.getStatusText() : "");
            } else {
                statusText = "错误";
            }
            statusLabel.setText("状态: " + statusText);
            timeLabel.setText("耗时: " + response.getResponseTime() + " ms");
            sizeLabel.setText("大小: " + formatSize(response.getResponseSize()));

            // 性能指标
            dnsLabel.setText("DNS: " + response.getDnsTime() + " ms");
            tcpLabel.setText("TCP: " + response.getTcpConnectTime() + " ms");
            ttfbLabel.setText("TTFB: " + response.getTtfb() + " ms");
            String proto = response.getProtocolVersion();
            protocolLabel.setText("协议: " + (proto != null && !proto.isEmpty() ? proto : "--"));

            String body = response.getResponseBody();
            currentBody = body != null ? body : "";
            currentHeaders = response.getResponseHeaders();

            headersTableModel.setRowCount(0);
            if (currentHeaders != null) {
                for (Map.Entry<String, String> entry : currentHeaders.entrySet()) {
                    headersTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
                }
            }
            if (headersTextMode) {
                headersTextArea.setText(convertHeadersToText());
                headersTextArea.setCaretPosition(0);
            }

            // Cookie
            loadCookies(currentHeaders);

            // 响应体渲染
            applyBodyFormat((String) bodyFormatCombo.getSelectedItem());
        });
    }

    /**
     * 根据指定格式渲染当前响应体并切换到对应视图。
     * "自动检测" 时根据 Content-Type (及内容启发式) 判定实际格式。
     * 图片类型 (image/*) 且有原始字节数据时自动切换到图片预览。
     *
     * @param format 格式选项 (FMT_AUTO / FMT_JSON / FMT_XML / FMT_HTML / FMT_PLAIN)
     */
    private void applyBodyFormat(String format) {
        String body = currentBody != null ? currentBody : "";

        // 自动检测时优先检查图片
        if (FMT_AUTO.equals(format) && isImageContentType()
                && currentResponse != null
                && currentResponse.getResponseBytes() != null
                && currentResponse.getResponseBytes().length > 0) {
            showImagePreview(currentResponse.getResponseBytes());
            return;
        }

        String effective = format;
        if (FMT_AUTO.equals(format)) {
            effective = detectBodyFormat(body);
        }

        switch (effective) {
            case FMT_JSON:
                jsonTextArea.setText(prettyPrintJsonIfPossible(body));
                jsonTextArea.setCaretPosition(0);
                bodyCardLayout.show(bodyCardPanel, VIEW_JSON);
                currentBodyView = VIEW_JSON;
                break;
            case FMT_XML:
                xmlTextArea.setText(prettyPrintXmlIfPossible(body));
                xmlTextArea.setCaretPosition(0);
                bodyCardLayout.show(bodyCardPanel, VIEW_XML);
                currentBodyView = VIEW_XML;
                break;
            case FMT_HTML:
                // 源码模式: 显示原始 HTML 文本; 预览模式: 注入 <base href> 后渲染
                if (htmlShowSource) {
                    htmlSourceArea.setText(body);
                    htmlSourceArea.setCaretPosition(0);
                    bodyCardLayout.show(bodyCardPanel, VIEW_HTML_SOURCE);
                    currentBodyView = VIEW_HTML_SOURCE;
                } else {
                    String renderedHtml = injectBaseHref(body);
                    htmlPane.setText(renderedHtml);
                    htmlPane.setCaretPosition(0);
                    bodyCardLayout.show(bodyCardPanel, VIEW_HTML);
                    currentBodyView = VIEW_HTML;
                    // JEditorPane 仅支持 HTML 3.2, 复杂页面可能渲染空白。
                    // 检测渲染结果: 若文本长度远小于原始 HTML (说明未渲染出内容),
                    // 自动回退到源码视图并提示用户使用"在浏览器中打开"。
                    String renderedText = htmlPane.getText();
                    if (renderedText != null && renderedText.trim().length() < Math.max(20, body.length() / 50)) {
                        htmlSourceArea.setText(body);
                        htmlSourceArea.setCaretPosition(0);
                        bodyCardLayout.show(bodyCardPanel, VIEW_HTML_SOURCE);
                        currentBodyView = VIEW_HTML_SOURCE;
                        log.info("HTML 预览渲染内容过少, 已自动回退到源码视图。可点击\"在浏览器中打开\"查看完整渲染");
                    }
                }
                break;
            case FMT_PLAIN:
            default:
                bodyTextArea.setText(body);
                bodyTextArea.setCaretPosition(0);
                bodyCardLayout.show(bodyCardPanel, VIEW_TEXT);
                currentBodyView = VIEW_TEXT;
                break;
        }
    }

    /**
     * 向 HTML 响应体注入 {@code <base href>} 标签, 使相对资源 (CSS/JS/图片) 能按请求 URL 的
     * 源站解析, 修复服务器环境下 JEditorPane 渲染 HTML 时相对资源全部 404 导致空白的问题。
     *
     * @param html 原始 HTML
     * @return 注入 base href 后的 HTML (无可用 URL 时返回原文)
     */
    private String injectBaseHref(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String requestUrl = currentResponse != null ? currentResponse.getRequestUrl() : null;
        if (requestUrl == null || requestUrl.trim().isEmpty()) {
            return html;
        }
        // 提取 origin (scheme://host[:port]) 作为 base href
        String base;
        try {
            URI uri = new URI(requestUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return html;
            }
            base = scheme + "://" + host;
            if (port > 0) {
                base += ":" + port;
            }
            base += "/";
        } catch (Exception e) {
            return html;
        }
        String baseTag = "<base href=\"" + base + "\">";
        // 注入到 <head> 之后; 无 head 则注入到 <html> 之后; 都无则前置
        String lower = html.toLowerCase();
        int headIdx = lower.indexOf("<head");
        if (headIdx >= 0) {
            int closeIdx = lower.indexOf('>', headIdx);
            if (closeIdx >= 0) {
                return html.substring(0, closeIdx + 1) + baseTag + html.substring(closeIdx + 1);
            }
        }
        int htmlIdx = lower.indexOf("<html");
        if (htmlIdx >= 0) {
            int closeIdx = lower.indexOf('>', htmlIdx);
            if (closeIdx >= 0) {
                return html.substring(0, closeIdx + 1) + baseTag + html.substring(closeIdx + 1);
            }
        }
        return baseTag + html;
    }

    /**
     * 将当前响应体写入临时 HTML 文件, 用系统默认浏览器打开。
     * <p>
     * 服务器环境下 JEditorPane 的 HTML 渲染能力有限 (仅 HTML 3.2), 复杂页面常显示空白,
     * 通过系统浏览器可获得完整渲染。
     * 兼容 Windows Server: 优先 Desktop.browse, 失败则尝试 rundll32 / cmd start 兜底。
     */
    private void openInBrowser() {
        String body = currentBody;
        if (body == null || body.isEmpty()) {
            return;
        }
        try {
            // 注入 base href 后写入临时文件
            String html = injectBaseHref(body);
            java.io.File tempFile = java.io.File.createTempFile("jpostman-preview-", ".html");
            tempFile.deleteOnExit();
            java.nio.file.Files.write(tempFile.toPath(),
                    html.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            boolean opened = false;
            // 方式 1: Desktop API (适用于有桌面环境的系统)
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(tempFile.toURI());
                    opened = true;
                } catch (Exception e) {
                    log.warn("Desktop.browse 失败, 尝试命令行兜底: {}", e.getMessage());
                }
            }
            // 方式 2: Windows 命令行兜底 (适用于 Windows Server 无桌面环境)
            if (!opened) {
                String osName = System.getProperty("os.name", "").toLowerCase();
                if (osName.contains("win")) {
                    Runtime.getRuntime().exec(
                            new String[]{"cmd", "/c", "start", "", tempFile.getAbsolutePath()});
                    opened = true;
                }
            }
            if (!opened) {
                log.warn("无法自动打开浏览器, 临时文件已保存: {}", tempFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("在浏览器中打开失败: {}", e.getMessage());
        }
    }

    /**
     * 显示图片预览。
     *
     * @param bytes 响应体原始字节
     */
    private void showImagePreview(byte[] bytes) {
        ImageIcon icon = new ImageIcon(bytes);
        imageLabel.setIcon(icon);
        imageLabel.setText(icon.getImageLoadStatus() == java.awt.MediaTracker.ERRORED
                ? "无法加载图片" : null);
        bodyCardLayout.show(bodyCardPanel, VIEW_IMAGE);
        currentBodyView = VIEW_IMAGE;
    }

    /**
     * 判断当前响应的 Content-Type 是否为图片类型 (image/*)。
     *
     * @return true 如果 Content-Type 以 image/ 开头
     */
    private boolean isImageContentType() {
        String contentType = getContentTypeHeader();
        return contentType != null && contentType.toLowerCase().startsWith("image/");
    }

    /**
     * 检测响应体格式。优先依据 Content-Type, 缺失时按内容启发式判断。
     *
     * @param body 响应体原文
     * @return 实际格式 (FMT_JSON / FMT_XML / FMT_HTML / FMT_PLAIN)
     */
    private String detectBodyFormat(String body) {
        String contentType = getContentTypeHeader();
        if (contentType != null && !contentType.isEmpty()) {
            String ct = contentType.toLowerCase();
            if (ct.contains("application/json")) {
                return FMT_JSON;
            }
            if (ct.contains("application/xml") || ct.contains("text/xml") || ct.contains("+xml")) {
                return FMT_XML;
            }
            if (ct.contains("text/html")) {
                return FMT_HTML;
            }
        }
        // 内容启发式检测
        if (body != null && !body.trim().isEmpty()) {
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return FMT_JSON;
            }
            if (trimmed.startsWith("<")) {
                String lower = trimmed.toLowerCase();
                if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) {
                    return FMT_HTML;
                }
                return FMT_XML;
            }
        }
        return FMT_PLAIN;
    }

    /**
     * 从当前响应头中查找 Content-Type (大小写不敏感)。
     */
    private String getContentTypeHeader() {
        if (currentHeaders == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : currentHeaders.entrySet()) {
            if (entry.getKey() != null && "content-type".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 从响应头中解析 Set-Cookie 并填充到 Cookie 表格。
     * 支持多个 Set-Cookie 头 (以换行分隔的情况)。
     *
     * @param headers 响应头 Map
     */
    private void loadCookies(Map<String, String> headers) {
        cookiesTableModel.setRowCount(0);
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey())) {
                String value = entry.getValue();
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                // 多个 Cookie 可能以换行分隔
                String[] cookies = value.split("\n");
                for (String cookie : cookies) {
                    parseAndAddCookie(cookie.trim());
                }
            }
        }
    }

    /**
     * 解析单个 Set-Cookie 值并添加到 Cookie 表格。
     * 格式: name=value; Path=/; Domain=.example.com; Expires=...; HttpOnly; Secure
     *
     * @param cookieStr Set-Cookie 头值
     */
    private void parseAndAddCookie(String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) {
            return;
        }
        String[] parts = cookieStr.split(";");
        if (parts.length == 0) {
            return;
        }

        // 第一部分是 name=value
        String nameValue = parts[0].trim();
        int eqIdx = nameValue.indexOf('=');
        String name = eqIdx >= 0 ? nameValue.substring(0, eqIdx).trim() : nameValue;
        String value = eqIdx >= 0 ? nameValue.substring(eqIdx + 1).trim() : "";

        String path = "";
        String domain = "";
        String expires = "";

        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            int attrEq = attr.indexOf('=');
            String attrName = attrEq >= 0 ? attr.substring(0, attrEq).trim() : attr;
            String attrValue = attrEq >= 0 ? attr.substring(attrEq + 1).trim() : "";

            if ("path".equalsIgnoreCase(attrName)) {
                path = attrValue;
            } else if ("domain".equalsIgnoreCase(attrName)) {
                domain = attrValue;
            } else if ("expires".equalsIgnoreCase(attrName)) {
                expires = attrValue;
            } else if ("max-age".equalsIgnoreCase(attrName) && expires.isEmpty()) {
                expires = "Max-Age=" + attrValue;
            }
        }

        cookiesTableModel.addRow(new Object[]{name, value, path, domain, expires});
    }

    /**
     * 尝试将文本解析为 JSON 并美化输出。解析失败时返回原始文本。
     * 仅对 JSON 对象 ({}) 和数组 ([]) 进行美化, 标量值 (字符串/数字/布尔) 返回原文。
     *
     * @param text 待美化的文本
     * @return 美化后的 JSON 字符串, 或原始文本
     */
    private String prettyPrintJsonIfPossible(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            // 仅对对象或数组美化, 避免将纯字符串/数字误处理
            if (node.isObject() || node.isArray()) {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            }
        } catch (Exception e) {
            // 非 JSON 内容, 保留原文
            log.debug("响应体非 JSON, 保留原文: {}", e.getMessage());
        }
        return text;
    }

    /**
     * 尝试将文本解析为 XML 并美化输出 (缩进 2 空格)。解析失败时返回原始文本。
     * 禁用外部实体解析以防范 XXE。
     *
     * @param text 待美化的文本
     * @return 美化后的 XML 字符串, 或原始文本
     */
    private String prettyPrintXmlIfPossible(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {
                // 某些实现不支持这些 feature, 忽略
            }
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(text)));

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            log.debug("响应体非 XML, 保留原文: {}", e.getMessage());
        }
        return text;
    }

    /**
     * 切换响应头表格模式与文本模式。
     * 表格 -> 文本: 将当前 headers 转为 "Name: Value\n" 格式;
     * 文本 -> 表格: 直接切回表格 (表格已持有数据, 无需反向解析)。
     */
    private void toggleHeadersMode() {
        if (!headersTextMode) {
            headersTextArea.setText(convertHeadersToText());
            headersTextArea.setCaretPosition(0);
            headersTextMode = true;
            headersCardLayout.show(headersCardPanel, VIEW_HDR_TEXT);
            headersModeToggleButton.setText("切换为表格模式");
        } else {
            headersTextMode = false;
            headersCardLayout.show(headersCardPanel, VIEW_HDR_TABLE);
            headersModeToggleButton.setText("切换为文本模式");
        }
    }

    /**
     * 将响应头表格数据转为 "Name: Value\n" 文本, 便于查看和复制。
     */
    private String convertHeadersToText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headersTableModel.getRowCount(); i++) {
            Object nameObj = headersTableModel.getValueAt(i, 0);
            Object valueObj = headersTableModel.getValueAt(i, 1);
            String name = nameObj != null ? nameObj.toString() : "";
            String value = valueObj != null ? valueObj.toString() : "";
            sb.append(name).append(": ").append(value).append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取当前显示的响应体文本 (用于复制)。
     */
    private String getDisplayedBodyText() {
        switch (currentBodyView) {
            case VIEW_JSON:
                return jsonTextArea.getText();
            case VIEW_XML:
                return xmlTextArea.getText();
            case VIEW_HTML:
                return htmlPane.getText();
            case VIEW_HTML_SOURCE:
                return htmlSourceArea.getText();
            case VIEW_IMAGE:
                return currentBody != null ? currentBody : "";
            case VIEW_TEXT:
            default:
                return bodyTextArea.getText();
        }
    }

    /**
     * 将当前显示的响应体复制到系统剪贴板。
     */
    private void copyBodyToClipboard() {
        String text = getDisplayedBodyText();
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * 程序化设置响应体格式 (更新下拉框但不触发重复渲染, 随后渲染一次)。
     */
    private void setBodyFormat(String format) {
        suppressFormatListener = true;
        try {
            bodyFormatCombo.setSelectedItem(format);
        } finally {
            suppressFormatListener = false;
        }
        applyBodyFormat(format);
    }

    /**
     * 重置性能指标标签。
     */
    private void resetMetrics() {
        dnsLabel.setText("DNS: --");
        tcpLabel.setText("TCP: --");
        ttfbLabel.setText("TTFB: --");
        protocolLabel.setText("协议: --");
    }

    /**
     * 状态栏显示 "Sending...", 清空 Body、Headers、Cookie 和性能指标。
     */
    public void showLoading() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("状态: 发送中...");
            timeLabel.setText("耗时: --");
            sizeLabel.setText("大小: --");
            resetMetrics();
            currentBody = "";
            currentHeaders = null;
            currentResponse = null;
            imageLabel.setIcon(null);
            headersTableModel.setRowCount(0);
            cookiesTableModel.setRowCount(0);
            if (headersTextMode) {
                headersTextArea.setText("");
            }
            applyBodyFormat((String) bodyFormatCombo.getSelectedItem());
        });
    }

    /**
     * 状态栏显示错误, Body 以纯文本显示错误信息。
     *
     * @param message 错误信息
     */
    public void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("状态: 错误");
            timeLabel.setText("耗时: --");
            sizeLabel.setText("大小: --");
            resetMetrics();
            String errorText = "错误: " + (message != null ? message : "Unknown error");
            currentBody = errorText;
            currentHeaders = null;
            currentResponse = null;
            imageLabel.setIcon(null);
            headersTableModel.setRowCount(0);
            cookiesTableModel.setRowCount(0);
            if (headersTextMode) {
                headersTextArea.setText("");
            }
            // 错误信息按纯文本展示
            setBodyFormat(FMT_PLAIN);
        });
    }

    /**
     * 清空所有内容。
     */
    public void clear() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("状态: --");
            timeLabel.setText("耗时: --");
            sizeLabel.setText("大小: --");
            resetMetrics();
            currentBody = "";
            currentHeaders = null;
            currentResponse = null;
            imageLabel.setIcon(null);
            headersTableModel.setRowCount(0);
            cookiesTableModel.setRowCount(0);
            if (headersTextMode) {
                headersTextArea.setText("");
            }
            applyBodyFormat((String) bodyFormatCombo.getSelectedItem());
        });
    }

    /**
     * 格式化字节数为可读字符串。
     */
    private String formatSize(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
