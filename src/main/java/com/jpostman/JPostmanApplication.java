package com.jpostman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.jpostman.config.JPostmanConfig;
import com.jpostman.model.Settings;
import com.jpostman.ui.frame.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import java.awt.Font;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JPostman 启动类 — v2 架构。
 * <p>
 * 同时承担 SpringBoot 容器引导和 Swing 窗口创建两个职责。
 * <p>
 * 启动流程:
 * 1. 设置 FlatLaf 外观 (在创建任何 Swing 组件之前)
 * 2. 确保用户数据目录存在 (~/.api-client/)
 * 3. 以非 Web 模式启动 SpringBoot 容器
 * 4. 在 EDT 中从容器获取 MainFrame 并显示
 * 5. 注册关闭钩子, 窗口关闭时优雅关闭 SpringBoot
 */
public class JPostmanApplication {

    private static final Logger log = LoggerFactory.getLogger(JPostmanApplication.class);

    /** 数据根目录名 */
    private static final String DATA_DIR_NAME = ".api-client";

    public static void main(String[] args) {
        // 1. 设置 FlatLaf 外观 (必须在创建任何 Swing 组件之前)
        setupLookAndFeel();

        // 2. 确保用户数据目录结构存在
        ensureUserDataDirs();

        // 3. 启动 SpringBoot 容器 (非 Web 模式, Swing 需要 non-headless)
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(JPostmanConfig.class)
                        .web(WebApplicationType.NONE)
                        .headless(false)
                        .run(args);

        // 4. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));

        // 5. 在 EDT 中创建并显示主窗口
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = context.getBean(MainFrame.class);
            mainFrame.setVisible(true);
        });
    }

    /**
     * 启动时设置 FlatLaf 外观。
     * <p>
     * 由于此时 Spring 上下文尚未启动, 无法使用 {@code SettingsStore} (Spring Bean),
     * 因此直接读取 {@code ~/.api-client/settings.json}:
     * <ul>
     *   <li>theme = "dark" → 使用 FlatDarculaLaf, 否则使用 FlatIntelliJLaf</li>
     *   <li>fontSize &gt; 0 → 覆盖 FlatLaf 默认字体大小</li>
     * </ul>
     * 文件不存在或解析失败时, 默认使用 FlatIntelliJLaf 且不覆盖字体。
     */
    private static void setupLookAndFeel() {
        String theme = "light";
        int fontSize = -1;

        Path settingsFile = Paths.get(System.getProperty("user.home"), DATA_DIR_NAME, "settings.json");
        File file = settingsFile.toFile();
        if (file.isFile()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Settings settings = mapper.readValue(file, Settings.class);
                if (settings != null) {
                    if (settings.getTheme() != null) {
                        theme = settings.getTheme();
                    }
                    if (settings.getFontSize() > 0) {
                        fontSize = settings.getFontSize();
                    }
                }
            } catch (Exception e) {
                log.warn("解析设置文件失败 {}: {}", settingsFile, e.getMessage());
            }
        }

        if ("dark".equalsIgnoreCase(theme)) {
            FlatLaf.setup(new FlatDarculaLaf());
        } else {
            FlatLaf.setup(new FlatIntelliJLaf());
        }

        // 应用字体大小 (覆盖 FlatLaf 默认字体)
        if (fontSize > 0) {
            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            Font baseFont = defaults.getFont("defaultFont");
            Font newFont = baseFont != null
                    ? baseFont.deriveFont((float) fontSize)
                    : new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
            defaults.put("defaultFont", newFont);
        }
    }

    /**
     * 确保用户数据目录结构存在。
     * <pre>
     * ~/.api-client/
     *   ├── collections/      (集合 JSON 文件)
     *   ├── environments/     (环境 JSON 文件)
     *   ├── plugins/loaded/   (已加载插件)
     *   ├── plugins/disabled/ (已禁用插件)
     *   ├── plugins/logs/     (插件日志)
     *   └── reports/          (性能测试报告)
     * </pre>
     */
    private static void ensureUserDataDirs() {
        Path dataDir = Paths.get(System.getProperty("user.home"), DATA_DIR_NAME);
        String[] subDirs = {
                "collections",
                "environments",
                "plugins/loaded",
                "plugins/disabled",
                "plugins/logs",
                "reports"
        };
        for (String sub : subDirs) {
            File dir = dataDir.resolve(sub).toFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }
    }
}
