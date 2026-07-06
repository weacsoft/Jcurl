package com.jpostman2.cli;

import com.jpostman2.config.AppConfig;
import com.jpostman2.model.component.Header;
import com.jpostman2.model.dto.RequestConfig;
import com.jpostman2.model.dto.ResponseData;
import com.jpostman2.model.dto.TimingMetrics;
import com.jpostman2.service.HttpEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * JPostman CLI 入口点 — curl 兼容的命令行 HTTP 客户端。
 * <p>
 * 使用 {@link CurlArgParser} 解析命令行参数,通过 Spring 引导 {@link HttpEngineService} 执行请求,
 * 并按 curl 兼容的方式输出结果。
 * <p>
 * 退出码:
 * <ul>
 *   <li>0 — 成功</li>
 *   <li>1 — 连接错误</li>
 *   <li>2 — 参数错误</li>
 *   <li>HTTP 状态码末位数字 — 4xx/5xx 错误 (如 404→4)</li>
 * </ul>
 */
public class CliLauncher {

    private static final Logger log = LoggerFactory.getLogger(CliLauncher.class);

    private static final String VERSION = "0.1.0";

    /**
     * CLI 主入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 设置 stdout/stderr 为 UTF-8 编码 (Windows 兼容)
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        // 空参数快速检查
        if (args == null || args.length == 0) {
            printHelp();
            System.exit(0);
        }

        // 先解析参数以检测 --help / --version
        CurlArgParser parser = new CurlArgParser();
        CurlArgParser.CliParseResult parseResult = parser.parse(args);
        CurlArgParser.CliOptions options = parseResult.getOptions();

        // 处理 --help
        if (options.isHelp()) {
            printHelp();
            System.exit(0);
        }

        // 处理 --version
        if (options.isVersion()) {
            printVersion();
            System.exit(0);
        }

        // 参数错误检查
        if (parseResult.hasErrors()) {
            for (String error : parseResult.getErrors()) {
                System.err.println("错误: " + error);
            }
            System.err.println("使用 -h 或 --help 查看帮助信息");
            System.exit(2);
        }

        // 引导 Spring 上下文并执行请求
        ConfigurableApplicationContext ctx = null;
        try {
            ctx = new SpringApplicationBuilder(AppConfig.class)
                    .web(WebApplicationType.NONE)
                    .headless(true)
                    .logStartupInfo(false)
                    .run();

            HttpEngineService engine = ctx.getBean(HttpEngineService.class);
            RequestConfig config = parseResult.getConfig();

            // 执行请求
            ResponseData response;
            try {
                response = engine.execute(config, null);
            } catch (Exception e) {
                log.error("请求执行失败", e);
                if (!options.isSilent()) {
                    System.err.println("请求执行失败: " + e.getMessage());
                }
                System.exit(1);
                return;
            }

            // 详细模式: 输出请求/响应详情到 stderr
            if (options.isVerbose()) {
                printVerbose(config, response);
            }

            // 输出处理
            outputResponse(response, config, options);

            // 确定退出码
            int exitCode = determineExitCode(response);
            System.exit(exitCode);

        } catch (Exception e) {
            log.error("CLI 启动失败", e);
            if (!options.isSilent()) {
                System.err.println("启动失败: " + e.getMessage());
            }
            System.exit(1);
        } finally {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    // ==================== 输出处理 ====================

    /**
     * 输出响应数据 (响应头 + 响应体 + write-out 格式)。
     */
    private static void outputResponse(ResponseData response, RequestConfig config,
                                       CurlArgParser.CliOptions options) {
        // 错误响应处理
        if (response.getError() != null) {
            if (!options.isSilent()) {
                System.err.println("请求失败: " + response.getError());
            }
            return;
        }

        // 确定输出目标: 文件或 stdout
        OutputStream out;
        boolean closeAfter;
        if (options.getOutput() != null) {
            try {
                out = Files.newOutputStream(Path.of(options.getOutput()));
                closeAfter = true;
            } catch (IOException e) {
                log.error("无法打开输出文件: {}", options.getOutput(), e);
                if (!options.isSilent()) {
                    System.err.println("无法打开输出文件: " + options.getOutput());
                }
                return;
            }
        } else {
            out = new FileOutputStream(FileDescriptor.out);
            closeAfter = false;
        }

        try {
            // 输出响应头 (如果 -i)
            if (options.isIncludeHeaders()) {
                String headersBlock = formatHeadersBlock(response);
                out.write(headersBlock.getBytes(StandardCharsets.UTF_8));
            }

            // 输出响应体
            byte[] bodyBytes = getBodyBytes(response);
            if (bodyBytes.length > 0) {
                out.write(bodyBytes);
            }
            out.flush();
        } catch (IOException e) {
            log.error("输出响应失败", e);
            if (!options.isSilent()) {
                System.err.println("输出响应失败: " + e.getMessage());
            }
        } finally {
            if (closeAfter) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }

        // write-out 格式输出 (始终到 stdout)
        if (options.getWriteOut() != null) {
            String writeOutStr = processWriteOut(options.getWriteOut(), response, config);
            System.out.print(writeOutStr);
            System.out.flush();
        }
    }

    /**
     * 获取响应体的字节数组。
     * 优先使用二进制 Base64 内容,其次使用文本 body。
     */
    private static byte[] getBodyBytes(ResponseData response) {
        if (response.getBinaryBase64() != null && !response.getBinaryBase64().isEmpty()) {
            return Base64.getDecoder().decode(response.getBinaryBase64());
        }
        if (response.getBody() != null) {
            return response.getBody().getBytes(StandardCharsets.UTF_8);
        }
        return new byte[0];
    }

    /**
     * 格式化响应头块 (用于 -i 输出)。
     */
    private static String formatHeadersBlock(ResponseData response) {
        StringBuilder sb = new StringBuilder();
        // 状态行
        String protocol = formatProtocol(response.getProtocol());
        sb.append(protocol).append(" ")
          .append(response.getStatusCode()).append(" ")
          .append(response.getStatusText() != null ? response.getStatusText() : "")
          .append("\r\n");
        // 响应头
        for (Header h : response.getHeaders()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /**
     * 格式化 HTTP 协议版本字符串。
     * OkHttp 返回 "http/1.1" 或 "h2", 转换为 "HTTP/1.1" 或 "HTTP/2"。
     */
    private static String formatProtocol(String protocol) {
        if (protocol == null || protocol.isEmpty()) {
            return "HTTP/1.1";
        }
        if (protocol.startsWith("http/")) {
            return "HTTP/" + protocol.substring(5);
        }
        if ("h2".equalsIgnoreCase(protocol)) {
            return "HTTP/2";
        }
        return protocol.toUpperCase();
    }

    // ==================== write-out 格式处理 ====================

    /**
     * 处理 -w/--write-out 格式字符串。
     * <p>
     * 支持变量:
     * <ul>
     *   <li>%{http_code} — HTTP 状态码</li>
     *   <li>%{http_version} — HTTP 版本</li>
     *   <li>%{content_type} — Content-Type</li>
     *   <li>%{size_download} — 下载大小</li>
     *   <li>%{time_total} — 总耗时 (秒)</li>
     *   <li>%{time_connect} — 连接耗时</li>
     *   <li>%{time_starttransfer} — TTFB</li>
     *   <li>%{url_effective} — 最终 URL</li>
     *   <li>%{response_header} — 所有响应头</li>
     * </ul>
     * 支持转义: \n \t \r \\
     */
    private static String processWriteOut(String format, ResponseData response,
                                          RequestConfig config) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);

            // 变量 %{...}
            if (c == '%' && i + 1 < format.length() && format.charAt(i + 1) == '{') {
                int end = format.indexOf('}', i + 2);
                if (end >= 0) {
                    String var = format.substring(i + 2, end);
                    result.append(resolveWriteOutVar(var, response, config));
                    i = end + 1;
                    continue;
                }
            }

            // 转义序列
            if (c == '\\' && i + 1 < format.length()) {
                char next = format.charAt(i + 1);
                switch (next) {
                    case 'n': result.append('\n'); break;
                    case 't': result.append('\t'); break;
                    case 'r': result.append('\r'); break;
                    case '\\': result.append('\\'); break;
                    default: result.append(c).append(next); break;
                }
                i += 2;
                continue;
            }

            result.append(c);
            i++;
        }
        return result.toString();
    }

    /**
     * 解析单个 write-out 变量。
     */
    private static String resolveWriteOutVar(String var, ResponseData response,
                                             RequestConfig config) {
        TimingMetrics timing = response.getTiming();
        return switch (var) {
            case "http_code" -> String.valueOf(response.getStatusCode());
            case "http_version" -> formatProtocol(response.getProtocol());
            case "content_type" -> response.getContentType() != null ? response.getContentType() : "";
            case "size_download" -> String.valueOf(response.getSize());
            case "time_total" -> String.format("%.3f", timing.getTotalMs() / 1000.0);
            case "time_connect" -> String.format("%.3f", timing.getTcpMs() / 1000.0);
            case "time_starttransfer" -> String.format("%.3f", timing.getTtfbMs() / 1000.0);
            case "url_effective" -> config.getUrl() != null ? config.getUrl() : "";
            case "response_header" -> {
                StringBuilder sb = new StringBuilder();
                for (Header h : response.getHeaders()) {
                    sb.append(h.getKey()).append(": ").append(h.getValue()).append("\n");
                }
                yield sb.toString();
            }
            default -> "%{" + var + "}";
        };
    }

    // ==================== 详细模式输出 ====================

    /**
     * 输出详细的请求/响应信息到 stderr (用于 -v)。
     */
    private static void printVerbose(RequestConfig config, ResponseData response) {
        StringBuilder sb = new StringBuilder();

        // 请求信息
        sb.append("* Connecting to ").append(config.getUrl()).append("\n");
        sb.append("> ").append(config.getMethod()).append(" ")
          .append(extractPath(config.getUrl())).append(" HTTP/1.1\n");
        sb.append("> Host: ").append(extractHost(config.getUrl())).append("\n");
        for (Header h : config.getHeaders()) {
            if (h.isEnabled() && h.getKey() != null) {
                sb.append("> ").append(h.getKey()).append(": ").append(h.getValue()).append("\n");
            }
        }
        sb.append("> \n");

        // 响应信息
        if (response.getError() != null) {
            sb.append("* Error: ").append(response.getError()).append("\n");
        } else {
            sb.append("< ").append(formatProtocol(response.getProtocol())).append(" ")
              .append(response.getStatusCode()).append(" ")
              .append(response.getStatusText() != null ? response.getStatusText() : "")
              .append("\n");
            for (Header h : response.getHeaders()) {
                sb.append("< ").append(h.getKey()).append(": ").append(h.getValue()).append("\n");
            }
            sb.append("< \n");
            // 性能指标
            TimingMetrics timing = response.getTiming();
            sb.append("* DNS: ").append(timing.getDnsMs()).append("ms")
              .append(", TCP: ").append(timing.getTcpMs()).append("ms")
              .append(", TLS: ").append(timing.getTlsMs()).append("ms")
              .append(", TTFB: ").append(timing.getTtfbMs()).append("ms")
              .append(", Total: ").append(timing.getTotalMs()).append("ms\n");
        }

        System.err.print(sb);
        System.err.flush();
    }

    /** 从 URL 中提取路径部分 (含查询参数) */
    private static String extractPath(String url) {
        if (url == null) return "/";
        try {
            int schemeEnd = url.indexOf("://");
            int start = schemeEnd >= 0 ? url.indexOf('/', schemeEnd + 3) : url.indexOf('/');
            if (start < 0) return "/";
            return url.substring(start);
        } catch (Exception e) {
            return "/";
        }
    }

    /** 从 URL 中提取 Host 部分 */
    private static String extractHost(String url) {
        if (url == null) return "";
        try {
            int schemeEnd = url.indexOf("://");
            int start = schemeEnd >= 0 ? schemeEnd + 3 : 0;
            int end = url.indexOf('/', start);
            if (end < 0) end = url.length();
            // 去除查询参数和端口后的部分暂保留 (Host 含端口)
            int queryIdx = url.indexOf('?', start);
            if (queryIdx >= 0 && queryIdx < end) end = queryIdx;
            return url.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 退出码 ====================

    /**
     * 确定退出码。
     * <ul>
     *   <li>连接错误 (response.error != null) → 1</li>
     *   <li>HTTP 4xx/5xx → 状态码末位数字 (如 404→4)</li>
     *   <li>其他 → 0</li>
     * </ul>
     */
    private static int determineExitCode(ResponseData response) {
        if (response.getError() != null) {
            return 1;
        }
        int code = response.getStatusCode();
        if (code >= 400 && code <= 599) {
            return code % 10;
        }
        return 0;
    }

    // ==================== 帮助与版本 ====================

    /**
     * 打印帮助信息。
     */
    private static void printHelp() {
        System.out.print("""
JPostman CLI — curl 兼容的 HTTP 客户端

用法: java -jar jpostman-core.jar [选项] URL

选项:
  -X, --request METHOD      指定 HTTP 请求方法 (GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS)
  -G, --get                 将 -d 数据放入 URL 查询参数
  -H, --header HEADER       添加请求头 (格式: Key: Value)
  -A, --user-agent AGENT    设置 User-Agent
  -e, --referer URL         设置 Referer
      --compressed          请求压缩 (Accept-Encoding: gzip, deflate)
  -d, --data DATA           发送数据 (默认 POST, Content-Type: application/x-www-form-urlencoded)
                            支持 @file 语法读取文件
      --data-raw DATA       同 -d 但不解析 @file
      --data-binary DATA    二进制数据 (@file 读取不处理换行)
      --data-urlencode DATA URL 编码后发送
  -F, --form KEY=VALUE      multipart/form-data (支持 @file)
  -T, --upload-file FILE    上传文件 (PUT 模式)
  -u, --user USER:PASS      Basic 认证
      --bearer TOKEN        Bearer Token 认证
  -b, --cookie COOKIE       发送 Cookie (字符串或 @file)
  -o, --output FILE         输出到文件
  -i, --include             输出中包含响应头
  -s, --silent              静默模式
  -v, --verbose             详细模式
  -w, --write-out FORMAT    自定义输出格式
  -L, --location            跟随重定向
      --connect-timeout SEC 连接超时 (秒)
  -m, --max-time SEC        最大请求时间 (秒)
  -k, --insecure            跳过 SSL 验证
      --resolve HOST:PORT:ADDR  自定义 DNS 解析
      --url URL             显式指定 URL
  -h, --help                显示帮助信息
      --version             显示版本信息

write-out 变量:
  %{http_code}              HTTP 状态码
  %{http_version}           HTTP 版本
  %{content_type}           Content-Type
  %{size_download}          下载大小
  %{time_total}             总耗时 (秒)
  %{time_connect}           连接耗时
  %{time_starttransfer}     TTFB
  %{url_effective}          最终 URL
  %{response_header}        所有响应头
  \\n                        换行

退出码:
  0     成功
  1     连接错误
  2     参数错误
  3-9   HTTP 错误 (4xx/5xx 状态码末位数字)

示例:
  java -jar jpostman-core.jar https://httpbin.org/get
  java -jar jpostman-core.jar -X POST -d '{"key":"value"}' -H "Content-Type: application/json" https://httpbin.org/post
  java -jar jpostman-core.jar -u user:pass https://httpbin.org/basic-auth/user/pass
  java -jar jpostman-core.jar -s -o response.json -w "%{http_code}\\n" https://httpbin.org/get
""");
    }

    /**
     * 打印版本信息。
     */
    private static void printVersion() {
        System.out.println("JPostman CLI v" + VERSION);
    }
}
