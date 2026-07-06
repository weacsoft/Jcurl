package com.jcurl2.cli;

import com.jcurl2.model.component.AuthConfig;
import com.jcurl2.model.component.FormItem;
import com.jcurl2.model.component.Header;
import com.jcurl2.model.component.RequestBody;
import com.jcurl2.model.dto.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * curl 兼容的命令行参数解析器。
 * <p>
 * 将命令行参数 {@code String[]} 解析为 {@link RequestConfig} + {@link CliOptions}。
 * <p>
 * 支持的 curl 选项:
 * <ul>
 *   <li>请求方法: -X/--request, -G/--get</li>
 *   <li>Header: -H/--header, -A/--user-agent, -e/--referer, --compressed</li>
 *   <li>Body: -d/--data, --data-raw, --data-binary, --data-urlencode, -F/--form, -T/--upload-file</li>
 *   <li>认证: -u/--user, --bearer</li>
 *   <li>Cookie: -b/--cookie</li>
 *   <li>输出: -o/--output, -i/--include, -s/--silent, -v/--verbose, -w/--write-out</li>
 *   <li>连接: -L/--location, --connect-timeout, -m/--max-time, -k/--insecure, --resolve</li>
 *   <li>URL: 位置参数, --url</li>
 * </ul>
 * <p>
 * 解析特性:
 * <ul>
 *   <li>短选项组合: {@code -si} 等价于 {@code -s -i}</li>
 *   <li>等号赋值: {@code --request=POST} 等价于 {@code --request POST}</li>
 *   <li>紧贴赋值: {@code -XPOST} 等价于 {@code -X POST}</li>
 *   <li>@file 语法: {@code -d @data.json} 读取文件内容</li>
 *   <li>多次 -H 和 -F 可累积</li>
 * </ul>
 */
public class CurlArgParser {

    private static final Logger log = LoggerFactory.getLogger(CurlArgParser.class);

    /** 需要值的短选项 */
    private static final Set<Character> SHORT_VALUE_OPTS = Set.of(
            'X', 'H', 'A', 'e', 'd', 'F', 'T', 'u', 'b', 'o', 'w', 'm'
    );

    /** 标志型短选项 (不需要值) */
    private static final Set<Character> SHORT_FLAG_OPTS = Set.of(
            'G', 'i', 's', 'v', 'L', 'k', 'h'
    );

    /** 需要值的长选项 */
    private static final Set<String> LONG_VALUE_OPTS = Set.of(
            "request", "header", "user-agent", "referer", "data", "data-raw",
            "data-binary", "data-urlencode", "form", "upload-file", "user",
            "bearer", "cookie", "output", "write-out", "connect-timeout",
            "max-time", "resolve", "url"
    );

    /**
     * 解析命令行参数。
     *
     * @param args 命令行参数数组
     * @return 解析结果,包含 RequestConfig、CliOptions 和错误列表
     */
    public CliParseResult parse(String[] args) {
        CliParseResult result = new CliParseResult();
        RequestConfig config = result.getConfig();
        CliOptions options = result.getOptions();
        List<String> errors = result.getErrors();

        if (args == null || args.length == 0) {
            errors.add("未提供参数");
            return result;
        }

        // 工作变量
        StringBuilder dataBuilder = new StringBuilder();
        boolean hasData = false;
        List<FormItem> formItems = new ArrayList<>();
        boolean hasForm = false;
        String uploadFile = null;
        boolean useGet = false;
        String url = null;
        boolean methodExplicitlySet = false;
        boolean contentTypeUserSet = false;

        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (arg == null || arg.isEmpty()) {
                i++;
                continue;
            }

            if (arg.startsWith("--")) {
                // ---- 长选项 ----
                String name;
                String inlineValue = null;
                int eq = arg.indexOf('=');
                if (eq >= 0) {
                    name = arg.substring(2, eq);
                    inlineValue = arg.substring(eq + 1);
                } else {
                    name = arg.substring(2);
                }

                boolean takesValue = LONG_VALUE_OPTS.contains(name);
                String value = inlineValue;
                if (takesValue && value == null) {
                    i++;
                    if (i >= args.length) {
                        errors.add("选项 --" + name + " 需要参数");
                        break;
                    }
                    value = args[i];
                }

                switch (name) {
                    // 请求方法类
                    case "request":
                        config.setMethod(value.toUpperCase());
                        methodExplicitlySet = true;
                        break;
                    case "get":
                        useGet = true;
                        break;
                    // Header 类
                    case "header":
                        contentTypeUserSet |= parseHeader(value, config, errors);
                        break;
                    case "user-agent":
                        config.getHeaders().add(new Header("User-Agent", value));
                        break;
                    case "referer":
                        config.getHeaders().add(new Header("Referer", value));
                        break;
                    case "compressed":
                        config.getHeaders().add(new Header("Accept-Encoding", "gzip, deflate"));
                        break;
                    // Body 类
                    case "data":
                        appendData(dataBuilder, resolveDataValue(value, true, false, errors));
                        hasData = true;
                        break;
                    case "data-raw":
                        appendData(dataBuilder, value);
                        hasData = true;
                        break;
                    case "data-binary":
                        appendData(dataBuilder, resolveDataValue(value, true, true, errors));
                        hasData = true;
                        break;
                    case "data-urlencode":
                        appendData(dataBuilder, resolveUrlEncodeData(value, errors));
                        hasData = true;
                        break;
                    case "form":
                        parseFormValue(value, formItems, errors);
                        hasForm = true;
                        break;
                    case "upload-file":
                        uploadFile = value;
                        break;
                    // 认证类
                    case "user":
                        parseUserAuth(value, config);
                        break;
                    case "bearer":
                        AuthConfig bearer = new AuthConfig("bearer");
                        bearer.setToken(value);
                        config.setAuth(bearer);
                        break;
                    // Cookie 类
                    case "cookie":
                        String cookie = resolveCookieValue(value, errors);
                        if (cookie != null) {
                            config.getHeaders().add(new Header("Cookie", cookie));
                        }
                        break;
                    // 输出控制类
                    case "output":
                        options.setOutput(value);
                        break;
                    case "include":
                        options.setIncludeHeaders(true);
                        break;
                    case "silent":
                        options.setSilent(true);
                        break;
                    case "verbose":
                        options.setVerbose(true);
                        break;
                    case "write-out":
                        options.setWriteOut(value);
                        break;
                    // 连接控制类
                    case "location":
                        options.setFollowRedirects(true);
                        break;
                    case "connect-timeout":
                        try {
                            options.setConnectTimeout(Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            errors.add("无效的连接超时值: " + value);
                        }
                        break;
                    case "max-time":
                        try {
                            options.setMaxTime(Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            errors.add("无效的最大时间值: " + value);
                        }
                        break;
                    case "insecure":
                        options.setInsecure(true);
                        break;
                    case "resolve":
                        options.setResolve(value);
                        break;
                    // URL
                    case "url":
                        if (url == null) {
                            url = value;
                        } else {
                            errors.add("重复的 URL 参数: " + value);
                        }
                        break;
                    // 帮助/版本
                    case "help":
                        options.setHelp(true);
                        break;
                    case "version":
                        options.setVersion(true);
                        break;
                    default:
                        errors.add("未知选项: --" + name);
                        break;
                }
            } else if (arg.startsWith("-") && arg.length() > 1) {
                // ---- 短选项 (可能组合) ----
                String opts = arg.substring(1);
                int j = 0;
                while (j < opts.length()) {
                    char c = opts.charAt(j);
                    if (SHORT_FLAG_OPTS.contains(c)) {
                        switch (c) {
                            case 'G': useGet = true; break;
                            case 'i': options.setIncludeHeaders(true); break;
                            case 's': options.setSilent(true); break;
                            case 'v': options.setVerbose(true); break;
                            case 'L': options.setFollowRedirects(true); break;
                            case 'k': options.setInsecure(true); break;
                            case 'h': options.setHelp(true); break;
                        }
                        j++;
                    } else if (SHORT_VALUE_OPTS.contains(c)) {
                        // 获取值: 紧贴的剩余部分或下一个参数
                        String rest = j + 1 < opts.length() ? opts.substring(j + 1) : "";
                        String val;
                        if (!rest.isEmpty()) {
                            val = rest;
                        } else {
                            i++;
                            if (i >= args.length) {
                                errors.add("选项 -" + c + " 需要参数");
                                break;
                            }
                            val = args[i];
                        }

                        switch (c) {
                            case 'X':
                                config.setMethod(val.toUpperCase());
                                methodExplicitlySet = true;
                                break;
                            case 'H':
                                contentTypeUserSet |= parseHeader(val, config, errors);
                                break;
                            case 'A':
                                config.getHeaders().add(new Header("User-Agent", val));
                                break;
                            case 'e':
                                config.getHeaders().add(new Header("Referer", val));
                                break;
                            case 'd':
                                appendData(dataBuilder, resolveDataValue(val, true, false, errors));
                                hasData = true;
                                break;
                            case 'F':
                                parseFormValue(val, formItems, errors);
                                hasForm = true;
                                break;
                            case 'T':
                                uploadFile = val;
                                break;
                            case 'u':
                                parseUserAuth(val, config);
                                break;
                            case 'b':
                                String cookie = resolveCookieValue(val, errors);
                                if (cookie != null) {
                                    config.getHeaders().add(new Header("Cookie", cookie));
                                }
                                break;
                            case 'o':
                                options.setOutput(val);
                                break;
                            case 'w':
                                options.setWriteOut(val);
                                break;
                            case 'm':
                                try {
                                    options.setMaxTime(Integer.parseInt(val));
                                } catch (NumberFormatException e) {
                                    errors.add("无效的最大时间值: " + val);
                                }
                                break;
                        }
                        j = opts.length(); // 已消费剩余部分
                    } else {
                        errors.add("未知选项: -" + c);
                        j++;
                    }
                }
            } else {
                // ---- 位置参数 = URL ----
                if (url == null) {
                    url = arg;
                } else {
                    errors.add("多余的参数: " + arg);
                }
            }
            i++;
        }

        // ==================== 后处理 ====================

        // 设置 URL
        if (url != null) {
            config.setUrl(url);
        } else if (!options.isHelp() && !options.isVersion()) {
            errors.add("未指定 URL");
        }

        // 冲突检查
        if (hasData && hasForm) {
            errors.add("不能同时使用 -d/--data 和 -F/--form");
        }
        if (hasData && uploadFile != null) {
            errors.add("不能同时使用 -d/--data 和 -T/--upload-file");
        }
        if (hasForm && uploadFile != null) {
            errors.add("不能同时使用 -F/--form 和 -T/--upload-file");
        }

        // 处理 -G: 将 -d 数据放入 URL 查询参数
        if (useGet) {
            if (hasData && dataBuilder.length() > 0) {
                String data = dataBuilder.toString();
                String currentUrl = config.getUrl();
                if (currentUrl != null && !currentUrl.isEmpty()) {
                    String sep = currentUrl.contains("?") ? "&" : "?";
                    config.setUrl(currentUrl + sep + data);
                }
            }
            hasData = false; // 数据已转入 URL, 不再放入 body
            if (!methodExplicitlySet) {
                config.setMethod("GET");
            }
        } else if (hasData) {
            // 有 -d 且未指定 -X, 默认方法为 POST
            if (!methodExplicitlySet) {
                config.setMethod("POST");
            }
            RequestBody body = new RequestBody("raw");
            body.setContent(dataBuilder.toString());
            body.setRawType("text");
            config.setBody(body);
            // 若用户未显式设置 Content-Type, 添加默认值
            if (!contentTypeUserSet) {
                config.getHeaders().add(new Header("Content-Type",
                        "application/x-www-form-urlencoded"));
            }
        }

        // 处理 -F: multipart/form-data
        if (hasForm) {
            RequestBody body = new RequestBody("form-data");
            body.setFormItems(formItems);
            config.setBody(body);
        }

        // 处理 -T: 上传文件 (PUT 模式)
        if (uploadFile != null) {
            if (!methodExplicitlySet) {
                config.setMethod("PUT");
            }
            RequestBody body = new RequestBody("binary");
            body.setFilePath(uploadFile);
            try {
                body.setFileName(Path.of(uploadFile).getFileName().toString());
            } catch (Exception e) {
                body.setFileName(uploadFile);
            }
            config.setBody(body);
        }

        return result;
    }

    // ==================== 辅助方法 ====================

    /**
     * 追加数据到累积器, 多次 -d 以 & 分隔。
     */
    private void appendData(StringBuilder builder, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(data);
    }

    /**
     * 解析 -d / --data / --data-binary 的值。
     * <p>
     * 支持 {@code @file} 语法读取文件内容。
     *
     * @param value       原始值
     * @param supportFile 是否支持 @file 语法 (-d 和 --data-binary 支持, --data-raw 不支持)
     * @param isBinary    是否为二进制模式 (--data-binary 不处理换行, -d 去除尾部换行)
     * @param errors      错误收集列表
     * @return 解析后的数据
     */
    private String resolveDataValue(String value, boolean supportFile, boolean isBinary,
                                    List<String> errors) {
        if (supportFile && value.startsWith("@")) {
            String filePath = value.substring(1);
            try {
                String content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
                if (!isBinary) {
                    // -d / --data 去除尾部换行符
                    content = content.replaceAll("[\\r\\n]+$", "");
                }
                return content;
            } catch (IOException e) {
                log.warn("读取数据文件失败: {}", filePath, e);
                errors.add("无法读取数据文件: " + filePath);
                return value;
            }
        }
        return value;
    }

    /**
     * 解析 --data-urlencode 的值。
     * <p>
     * 格式:
     * <ul>
     *   <li>{@code content} — URL 编码整个字符串</li>
     *   <li>{@code =content} — URL 编码 content, 无字段名</li>
     *   <li>{@code name=content} — URL 编码 content, 保留字段名</li>
     *   <li>{@code @filename} — 读取文件并 URL 编码</li>
     *   <li>{@code name@filename} — 读取文件并 URL 编码, 保留字段名</li>
     * </ul>
     */
    private String resolveUrlEncodeData(String value, List<String> errors) {
        int eqIdx = value.indexOf('=');
        int atIdx = value.indexOf('@');

        // name@filename 或 @filename
        if (atIdx >= 0 && (eqIdx < 0 || atIdx < eqIdx)) {
            String name = atIdx > 0 ? value.substring(0, atIdx) : null;
            String filePath = value.substring(atIdx + 1);
            try {
                String content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
                String encoded = URLEncoder.encode(content, StandardCharsets.UTF_8);
                if (name != null && !name.isEmpty()) {
                    return name + "=" + encoded;
                }
                return encoded;
            } catch (IOException e) {
                log.warn("读取数据文件失败: {}", filePath, e);
                errors.add("无法读取数据文件: " + filePath);
                return value;
            }
        }

        // name=content 或 =content
        if (eqIdx >= 0) {
            String name = value.substring(0, eqIdx);
            String content = value.substring(eqIdx + 1);
            String encoded = URLEncoder.encode(content, StandardCharsets.UTF_8);
            if (!name.isEmpty()) {
                return name + "=" + encoded;
            }
            return encoded;
        }

        // 纯 content, 编码全部
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 解析请求头字符串 (格式: "Key: Value")。
     *
     * @return 如果设置的是 Content-Type 则返回 true
     */
    private boolean parseHeader(String header, RequestConfig config, List<String> errors) {
        int idx = header.indexOf(':');
        if (idx < 0) {
            errors.add("无效的请求头格式 (缺少冒号): " + header);
            return false;
        }
        String key = header.substring(0, idx).trim();
        String value = header.substring(idx + 1).trim();
        if (key.isEmpty()) {
            errors.add("无效的请求头格式 (键为空): " + header);
            return false;
        }
        config.getHeaders().add(new Header(key, value));
        return key.equalsIgnoreCase("Content-Type");
    }

    /**
     * 解析 -F / --form 表单项。
     * <p>
     * 格式:
     * <ul>
     *   <li>{@code name=value} — 文本字段</li>
     *   <li>{@code name=@file} — 文件上传</li>
     *   <li>{@code name=@file;type=mime} — 指定 Content-Type 的文件上传</li>
     * </ul>
     */
    private void parseFormValue(String value, List<FormItem> formItems, List<String> errors) {
        int eqIdx = value.indexOf('=');
        if (eqIdx < 0) {
            errors.add("无效的表单参数 (缺少=): " + value);
            return;
        }
        String key = value.substring(0, eqIdx);
        String fieldValue = value.substring(eqIdx + 1);

        if (key.isEmpty()) {
            errors.add("无效的表单参数 (键为空): " + value);
            return;
        }

        if (fieldValue.startsWith("@")) {
            // 文件上传
            String fileSpec = fieldValue.substring(1);
            String filePath = fileSpec;
            // 解析 ;type= 语法
            int semiIdx = fileSpec.indexOf(';');
            if (semiIdx >= 0) {
                filePath = fileSpec.substring(0, semiIdx);
            }
            FormItem item = FormItem.file(key, filePath);
            try {
                item.setFileName(Path.of(filePath).getFileName().toString());
            } catch (Exception e) {
                item.setFileName(filePath);
            }
            formItems.add(item);
        } else {
            FormItem item = new FormItem(key, fieldValue);
            formItems.add(item);
        }
    }

    /**
     * 解析 Basic 认证参数 (格式: USER:PASSWORD)。
     */
    private void parseUserAuth(String value, RequestConfig config) {
        AuthConfig auth = new AuthConfig("basic");
        int idx = value.indexOf(':');
        if (idx >= 0) {
            auth.setUsername(value.substring(0, idx));
            auth.setPassword(value.substring(idx + 1));
        } else {
            auth.setUsername(value);
            auth.setPassword("");
        }
        config.setAuth(auth);
    }

    /**
     * 解析 Cookie 值, 支持 @file 语法。
     */
    private String resolveCookieValue(String value, List<String> errors) {
        if (value.startsWith("@")) {
            String filePath = value.substring(1);
            try {
                return Files.readString(Path.of(filePath), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                log.warn("读取 Cookie 文件失败: {}", filePath, e);
                errors.add("无法读取 Cookie 文件: " + filePath);
                return null;
            }
        }
        return value;
    }

    // ==================== 结果类 ====================

    /**
     * 解析结果,包含请求配置、CLI 选项和错误列表。
     */
    public static class CliParseResult {
        private RequestConfig config = new RequestConfig();
        private CliOptions options = new CliOptions();
        private List<String> errors = new ArrayList<>();

        public RequestConfig getConfig() { return config; }
        public void setConfig(RequestConfig config) { this.config = config; }

        public CliOptions getOptions() { return options; }
        public void setOptions(CliOptions options) { this.options = options; }

        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }

        /** 是否有解析错误 */
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }

    /**
     * CLI 选项 — 非请求配置的选项 (输出控制、连接控制等)。
     */
    public static class CliOptions {
        /** 输出文件路径 (-o) */
        private String output;
        /** 输出中包含响应头 (-i) */
        private boolean includeHeaders;
        /** 静默模式 (-s) */
        private boolean silent;
        /** 详细模式 (-v) */
        private boolean verbose;
        /** 自定义输出格式 (-w) */
        private String writeOut;
        /** 跟随重定向 (-L) */
        private boolean followRedirects;
        /** 连接超时秒数 (--connect-timeout) */
        private Integer connectTimeout;
        /** 最大请求时间秒数 (-m) */
        private Integer maxTime;
        /** 跳过 SSL 验证 (-k) */
        private boolean insecure;
        /** 自定义 DNS 解析 (--resolve) */
        private String resolve;
        /** 显示帮助 (-h / --help) */
        private boolean help;
        /** 显示版本 (--version) */
        private boolean version;

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public boolean isIncludeHeaders() { return includeHeaders; }
        public void setIncludeHeaders(boolean includeHeaders) { this.includeHeaders = includeHeaders; }

        public boolean isSilent() { return silent; }
        public void setSilent(boolean silent) { this.silent = silent; }

        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean verbose) { this.verbose = verbose; }

        public String getWriteOut() { return writeOut; }
        public void setWriteOut(String writeOut) { this.writeOut = writeOut; }

        public boolean isFollowRedirects() { return followRedirects; }
        public void setFollowRedirects(boolean followRedirects) { this.followRedirects = followRedirects; }

        public Integer getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Integer connectTimeout) { this.connectTimeout = connectTimeout; }

        public Integer getMaxTime() { return maxTime; }
        public void setMaxTime(Integer maxTime) { this.maxTime = maxTime; }

        public boolean isInsecure() { return insecure; }
        public void setInsecure(boolean insecure) { this.insecure = insecure; }

        public String getResolve() { return resolve; }
        public void setResolve(String resolve) { this.resolve = resolve; }

        public boolean isHelp() { return help; }
        public void setHelp(boolean help) { this.help = help; }

        public boolean isVersion() { return version; }
        public void setVersion(boolean version) { this.version = version; }
    }
}
