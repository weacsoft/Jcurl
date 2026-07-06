package com.jcurl2.plugin.extension;

import com.jcurl2.plugin.ExtensionPoint;
import com.jcurl2.plugin.PluginContext;
import javafx.scene.Node;

/**
 * UI 扩展点 — 向主界面注入自定义 UI 组件。
 * <p>
 * 典型场景:添加自定义标签页、侧边栏面板、工具栏按钮等。
 * <p>
 * 注意:此扩展点仅在 JavaFX UI 线程中调用。
 */
public interface UiExtension extends ExtensionPoint {

    /**
     * 获取扩展 UI 的展示位置。
     */
    enum UiPosition {
        /** 主界面左侧栏新增 Tab */
        SIDEBAR_TAB,
        /** 主界面顶部工具栏新增按钮 */
        TOOLBAR_BUTTON,
        /** 请求构建器新增 Tab */
        REQUEST_TAB,
        /** 响应展示区新增 Tab */
        RESPONSE_TAB
    }

    /**
     * 获取 UI 展示位置。
     *
     * @param ctx 插件上下文
     * @return 展示位置
     */
    UiPosition getPosition(PluginContext ctx);

    /**
     * 获取 Tab/按钮的标题文本。
     *
     * @param ctx 插件上下文
     * @return 标题
     */
    String getTitle(PluginContext ctx);

    /**
     * 构建 UI 组件节点。
     * <p>
     * 对于 SIDEBAR_TAB / REQUEST_TAB / RESPONSE_TAB:返回 Tab 的内容 Node。
     * 对于 TOOLBAR_BUTTON:返回 Button 节点。
     *
     * @param ctx 插件上下文
     * @return JavaFX Node
     */
    Node buildNode(PluginContext ctx);
}
