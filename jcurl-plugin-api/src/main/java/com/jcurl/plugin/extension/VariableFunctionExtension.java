package com.jcurl.plugin.extension;

import com.jcurl.plugin.ExtensionPoint;
import com.jcurl.plugin.PluginContext;

import java.util.List;

/**
 * 变量函数扩展点 — 注册自定义动态变量函数 {{$funcName}}。
 * <p>
 * 内置函数:{{$timestamp}}、{{$uuid}}、{{$randomInt}}、{{$datetime}}。
 * 插件可通过此扩展点添加更多函数,如 {{$md5}}、{{$base64}}、{{$now("yyyy-MM-dd")}} 等。
 */
public interface VariableFunctionExtension extends ExtensionPoint {

    /**
     * 获取此插件提供的函数名列表(不含 {{$}} 包裹)。
     * <p>
     * 如返回 ["md5", "base64"],则用户可在变量中使用 {{$md5}} 和 {{$base64}}。
     *
     * @param ctx 插件上下文
     * @return 函数名列表
     */
    List<String> getFunctionNames(PluginContext ctx);

    /**
     * 执行函数并返回结果。
     *
     * @param functionName 函数名(不含 {{$}})
     * @param args         函数参数(括号内的内容,已去除括号;无参数时为空字符串)
     * @param ctx          插件上下文
     * @return 函数执行结果;返回 null 表示函数不支持
     */
    String executeFunction(String functionName, String args, PluginContext ctx);
}
