package com.jcurl.plugin;

/**
 * 变量函数扩展点 — 扩展动态变量函数 (如 {{$myFunc}})。
 */
public interface VariableFunction {
    /** 返回函数名 (不含 $ 符号) */
    String getFunctionName();
    /** 执行函数, 返回结果字符串 */
    String execute();
}
