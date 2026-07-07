package com.jcurl2;

import com.jcurl2.config.AppConfig;
import com.jcurl2.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Jcurl 启动类 — 同时承担 SpringBoot 容器引导与 JavaFX Application 两个职责。
 * <p>
 * 启动流程:
 * <ol>
 *   <li>main() 在主线程启动 SpringBoot 容器(非 Web 模式)</li>
 *   <li>launch() 启动 JavaFX,阻塞直到窗口关闭</li>
 *   <li>start() 从 Spring 容器获取 MainView,显示主窗口</li>
 *   <li>stop() 窗口关闭时优雅关闭 SpringBoot 容器</li>
 * </ol>
 */
public class JcurlApplication extends Application {

    /** Spring 容器,在 main() 中创建,供 JavaFX 端获取 Bean */
    private static ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        // 1. 启动 SpringBoot 容器(非 Web 模式, 非 headless 以支持 JavaFX GUI)
        springContext = new SpringApplicationBuilder(AppConfig.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run(args);

        // 2. 启动 JavaFX(阻塞, 直到所有窗口关闭)
        launch(args);
    }

    /** 供非 Application 类获取 Spring 容器 */
    public static ConfigurableApplicationContext getSpringContext() {
        return springContext;
    }

    @Override
    public void start(Stage stage) throws Exception {
        // 3. 从 Spring 容器获取主视图(已注入所需 Service)
        MainView mainView = springContext.getBean(MainView.class);
        BorderPane root = mainView.getRoot();

        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(getClass().getResource("/css/dark.css").toExternalForm());
        stage.setScene(scene);

        // 将 Scene 传递给 MainView(用于主题切换和快捷键)
        mainView.setScene(scene);
        stage.setTitle("Jcurl - JavaFX API 客户端 (Win11)");
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        // 窗口关闭时退出应用
        stage.setOnCloseRequest(event -> {
            stage.close();
            Platform.exit();
        });

        stage.show();

        // 加载集合树数据(在 UI 显示后调用,确保 TreeView 已就绪)
        mainView.initialize();
    }

    @Override
    public void stop() {
        // 4. 窗口关闭时优雅关闭 SpringBoot 容器
        if (springContext != null) {
            springContext.close();
        }
    }
}
