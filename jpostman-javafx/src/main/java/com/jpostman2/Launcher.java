package com.jpostman2;

/**
 * 应用启动入口 — 不继承 Application,避免 JVM 使用 JavaFX 启动器。
 * <p>
 * 在 JavaFX 9+ 模块化环境下,若主类直接 extends Application,JVM 会使用 JavaFX 启动器,
 * 导致非模块化应用(JavaFX jar 在 classpath 而非 modulepath)报 "缺少 JavaFX 运行时组件"。
 * <p>
 * 通过独立的 Launcher 类作为入口,JVM 使用普通启动器,JavaFX 从 classpath 正常加载。
 * pom.xml 的 mainClass 指向此类。
 */
public class Launcher {

    public static void main(String[] args) {
        JPostman2Application.main(args);
    }
}
