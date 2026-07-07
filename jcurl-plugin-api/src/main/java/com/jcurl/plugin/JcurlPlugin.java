package com.jcurl.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插件标注注解 — 标注在插件主类上,提供元数据。
 * <p>
 * 示例:
 * <pre>{@code
 * @JcurlPlugin(name = "签名插件", description = "自动添加 API 签名", version = "1.0")
 * public class SignPlugin implements RequestInterceptor {
 *     @Override
 *     public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
 *         config.getHeaders().add(new Header("X-Sign", "abc123"));
 *         return config;
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JcurlPlugin {
    /** 插件名称 */
    String name() default "";
    /** 插件描述 */
    String description() default "";
    /** 插件版本 */
    String version() default "1.0.0";
    /** 作者 */
    String author() default "";
    /** 是否启用(可在运行时通过 PluginManager 修改) */
    boolean enabled() default true;
}
