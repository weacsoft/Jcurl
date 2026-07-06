import com.jpostman2.model.component.Header;
import com.jpostman2.model.dto.RequestConfig;
import com.jpostman2.model.dto.ResponseData;
import com.jpostman2.plugin.ExtensionPoint;
import com.jpostman2.plugin.JPostmanPlugin;
import com.jpostman2.plugin.PluginContext;
import com.jpostman2.plugin.extension.MetricsCollectorExtension;
import com.jpostman2.plugin.extension.RequestInterceptor;
import com.jpostman2.plugin.extension.ResponseInterceptor;
import com.jpostman2.plugin.extension.VariableFunctionExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPostman 演示插件 — 展示四大扩展点的用法。
 * <p>
 * 功能:
 * <ul>
 *   <li>请求拦截器:自动添加 X-Request-Id 和 X-Timestamp 头,记录请求日志</li>
 *   <li>响应拦截器:记录响应状态和耗时,提取响应中的 token 到日志</li>
 *   <li>变量函数:新增 {{$md5}}、{{$base64}}、{{$uuid2}} 三个动态变量</li>
 *   <li>指标采集:采集响应体大小(KB)和是否成功两个自定义指标</li>
 * </ul>
 * <p>
 * 安装方法: 将此文件复制到 ~/.api-client/plugins/ 目录,或在插件管理对话框中安装。
 */
@JPostmanPlugin(
        name = "演示插件",
        description = "展示请求拦截/响应拦截/变量函数/指标采集四大扩展点",
        version = "1.0.0",
        author = "JPostman"
)
public class DemoPlugin implements RequestInterceptor, ResponseInterceptor,
        VariableFunctionExtension, MetricsCollectorExtension {

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_TIMESTAMP = "X-Plugin-Timestamp";

    // ==================== 请求拦截器 ====================

    /**
     * 请求发送前:
     * 1. 生成唯一请求 ID 并添加到请求头
     * 2. 添加当前时间戳头
     * 3. 记录请求日志
     */
    @Override
    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
        // 生成唯一请求 ID
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        // 添加自定义请求头(如果用户未设置同名头)
        boolean hasRequestId = config.getHeaders().stream()
                .anyMatch(h -> HEADER_REQUEST_ID.equalsIgnoreCase(h.getKey()));
        if (!hasRequestId) {
            config.getHeaders().add(new Header(HEADER_REQUEST_ID, requestId));
        }

        // 添加时间戳头
        config.getHeaders().add(new Header(HEADER_TIMESTAMP,
                String.valueOf(System.currentTimeMillis())));

        // 通过 PluginContext 记录日志(显示在应用日志中)
        ctx.log("info", String.format("[演示插件] 发送请求: %s %s (RequestId=%s)",
                config.getMethod(), config.getUrl(), requestId));

        return config;
    }

    // ==================== 响应拦截器 ====================

    /**
     * 响应接收后:
     * 1. 记录响应状态码和耗时
     * 2. 尝试从 JSON 响应中提取 token 字段(演示响应体解析)
     */
    @Override
    public ResponseData afterResponse(ResponseData response, RequestConfig config, PluginContext ctx) {
        // 记录响应日志
        String timingInfo = response.getTiming() != null
                ? response.getTiming().getTotalMs() + "ms"
                : "未知";
        ctx.log("info", String.format("[演示插件] 收到响应: %d %s (耗时: %s, 大小: %d 字节)",
                response.getStatusCode(),
                response.getStatusText(),
                timingInfo,
                response.getSize()));

        // 演示: 如果响应体包含 "token",记录到日志(实际场景中可提取到环境变量)
        if (response.getBody() != null && response.getBody().contains("\"token\"")) {
            ctx.log("debug", "[演示插件] 检测到响应中包含 token 字段");
        }

        // 演示: 对错误响应添加标记头
        if (response.getStatusCode() >= 400) {
            response.getHeaders().add(new Header("X-Plugin-Error-Marked", "true"));
        }

        return response;
    }

    // ==================== 变量函数扩展 ====================

    /**
     * 注册三个自定义变量函数:
     * - {{$md5}}: 对参数计算 MD5 哈希
     * - {{$base64}}: 对参数进行 Base64 编码
     * - {{$uuid2}}: 生成不带横线的 UUID
     */
    @Override
    public List<String> getFunctionNames(PluginContext ctx) {
        return List.of("md5", "base64", "uuid2");
    }

    /**
     * 执行变量函数。
     * 参数说明:
     * - {{$md5}} → 参数为要哈希的文本,如 {{$md5(Hello)}}
     * - {{$base64}} → 参数为要编码的文本,如 {{$base64(Hello)}}
     * - {{$uuid2}} → 无参数,生成 32 位无横线 UUID
     */
    @Override
    public String executeFunction(String functionName, String args, PluginContext ctx) {
        try {
            switch (functionName) {
                case "md5":
                    return md5(args != null ? args : "");
                case "base64":
                    return Base64.getEncoder().encodeToString(
                            (args != null ? args : "").getBytes(StandardCharsets.UTF_8));
                case "uuid2":
                    return UUID.randomUUID().toString().replace("-", "");
                default:
                    return null;
            }
        } catch (Exception e) {
            ctx.log("error", "[演示插件] 变量函数执行失败: " + functionName + ", 错误: " + e.getMessage());
            return null;
        }
    }

    /** 计算 MD5 哈希(32位十六进制字符串) */
    private String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== 指标采集扩展 ====================

    /**
     * 采集自定义指标:
     * - response_kb: 响应体大小(KB)
     * - is_success: 请求是否成功(2xx/3xx = 1, 4xx/5xx = 0)
     */
    @Override
    public List<String> getMetricNames() {
        return List.of("response_kb", "is_success");
    }

    /**
     * 在性能测试中,每次请求完成后采集指标。
     * 这些指标会出现在性能测试报告中。
     */
    @Override
    public Map<String, Double> collectMetrics(RequestConfig config, ResponseData response, PluginContext ctx) {
        Map<String, Double> metrics = new HashMap<>();

        // 响应体大小(KB)
        double kb = response.getSize() / 1024.0;
        metrics.put("response_kb", Math.round(kb * 100.0) / 100.0);

        // 是否成功
        int code = response.getStatusCode();
        metrics.put("is_success", (code >= 200 && code < 400) ? 1.0 : 0.0);

        return metrics;
    }
}
