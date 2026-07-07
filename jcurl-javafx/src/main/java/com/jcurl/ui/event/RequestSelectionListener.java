package com.jcurl.ui.event;

import com.jcurl.model.Collection;
import com.jcurl.model.RequestNode;

/**
 * 请求选中监听器 — 当用户在集合树中点击请求节点时触发。
 * <p>
 * 由请求构建器(步骤 9)实现,集合树通过此接口通知请求构建器加载选中的请求配置。
 */
@FunctionalInterface
public interface RequestSelectionListener {

    /**
     * 请求被选中时调用。
     *
     * @param collection 请求所属集合(提供继承配置上下文)
     * @param request    被选中的请求节点
     */
    void onRequestSelected(Collection collection, RequestNode request);
}
