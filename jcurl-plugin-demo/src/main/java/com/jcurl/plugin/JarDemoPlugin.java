package com.jcurl.plugin;

import com.jcurl.plugin.model.component.Header;
import com.jcurl.plugin.model.dto.RequestConfig;
import com.jcurl.plugin.model.dto.ResponseData;
import com.jcurl.plugin.extension.RequestInterceptor;
import com.jcurl.plugin.extension.ResponseInterceptor;
import com.jcurl.plugin.extension.VariableFunctionExtension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JAR 插件演示 — 展示预编译 JAR 插件的开发方式。
 * <p>
 * 与 .java 源码插件不同,JAR 插件是预编译的字节码:
 * <ul>
 *   <li>不需要运行时编译器 (适用于 JRE 环境)</li>
 *   <li>可以使用第三方依赖 (打包进 fat JAR)</li>
 *   <li>适合分发和版本管理</li>
 * </ul>
 * <p>
 * 功能:
 * <ul>
 *   <li>请求拦截: 添加 X-Jar-Plugin 标记头和格式化时间戳</li>
 *   <li>响应拦截: 记录响应摘要,对 JSON 响应自动格式化检测</li>
 *   <li>变量函数: {{$urlencode}} URL 编码, {{$rfc1123date}} RFC1123 格式日期</li>
 * </ul>
 * <p>
 * 安装方法: 在插件管理对话框中选择 jcurl-plugin-demo-0.1.0-SNAPSHOT.jar 文件安装,
 * 或复制到 ~/.api-client/plugins/ 目录。
 */
@JcurlPlugin(
        name = "JAR 演示插件",
        description = "展示 JAR 预编译插件的开发方式 (URL编码/RFC日期/请求标记)",
        version = "1.0.0",
        author = "Jcurl"
)
public class JarDemoPlugin implements RequestInterceptor, ResponseInterceptor, VariableFunctionExtension {

    // ==================== 请求拦截器 ====================

    @Override
    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
        // 添加标记头
        boolean hasMarker = config.getHeaders().stream()
                .anyMatch(h -> "X-Jar-Plugin".equalsIgnoreCase(h.getKey()));
        if (!hasMarker) {
            config.getHeaders().add(new Header("X-Jar-Plugin", "true"));
        }

        // 添加 RFC1123 格式时间戳
        String rfcDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.now().atZone(ZoneId.systemDefault()));
        config.getHeaders().add(new Header("X-Plugin-Date", rfcDate));

        ctx.log("info", String.format("[JAR演示] 请求: %s %s",
                config.getMethod(), config.getUrl()));

        return config;
    }

    // ==================== 响应拦截器 ====================

    @Override
    public ResponseData afterResponse(ResponseData response, RequestConfig config, PluginContext ctx) {
        String body = response.getBody();
        boolean isJson = response.getContentType() != null
                && response.getContentType().contains("json");

        ctx.log("info", String.format("[JAR演示] 响应: %d (%s, %d 字节, JSON=%s)",
                response.getStatusCode(),
                response.getTiming() != null ? response.getTiming().getTotalMs() + "ms" : "?",
                response.getSize(),
                isJson));

        // 对 4xx/5xx 响应添加警告头
        if (response.getStatusCode() >= 400) {
            response.getHeaders().add(new Header("X-Plugin-Warning", "error-response"));
        }

        return response;
    }

    // ==================== 变量函数扩展 ====================

    @Override
    public List<String> getFunctionNames(PluginContext ctx) {
        return List.of("urlencode", "rfc1123date");
    }

    @Override
    public String executeFunction(String functionName, String args, PluginContext ctx) {
        try {
            switch (functionName) {
                case "urlencode":
                    return URLEncoder.encode(args != null ? args : "", StandardCharsets.UTF_8);
                case "rfc1123date":
                    return DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(Instant.now().atZone(ZoneId.systemDefault()));
                default:
                    return null;
            }
        } catch (Exception e) {
            ctx.log("error", "[JAR演示] 变量函数失败: " + functionName + " - " + e.getMessage());
            return null;
        }
    }
}
