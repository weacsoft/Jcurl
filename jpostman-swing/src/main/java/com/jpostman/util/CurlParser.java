package com.jpostman.util;

import com.jpostman.model.AuthConfig;
import com.jpostman.model.KeyValue;
import com.jpostman.model.RequestNode;

import java.util.ArrayList;
import java.util.List;

/**
 * cURL 命令解析器 — 将 cURL 命令文本解析为 RequestNode。
 * 支持: -X/--request, -H/--header, -d/--data, --data-raw, --data-binary, -b/--cookie, -u/--user, URL
 */
public class CurlParser {

    /**
     * 解析 cURL 命令文本。
     * @param curlText cURL 命令文本 (可包含换行和续行符 \)
     * @return 解析出的 RequestNode, 解析失败返回 null
     */
    public static RequestNode parse(String curlText) {
        if (curlText == null || curlText.trim().isEmpty()) {
            return null;
        }
        // 1. 清理: 去除换行续行符 \, 统一为单行
        String cleaned = curlText.trim()
                .replaceAll("\\\\\\s*\\n", " ")  // 续行符
                .replaceAll("\\s+", " ")          // 多空格合一
                .trim();
        // 2. 必须以 curl 开头
        if (!cleaned.toLowerCase().startsWith("curl")) {
            return null;
        }
        // 3. 用空格分割为 tokens, 注意引号内的空格不分割
        List<String> tokens = tokenize(cleaned);

        RequestNode request = new RequestNode("导入的请求");
        request.setMethod("GET");
        List<KeyValue> headers = new ArrayList<>();
        List<KeyValue> params = new ArrayList<>();
        String bodyData = null;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            switch (token) {
                case "-X":
                case "--request":
                    if (i + 1 < tokens.size()) {
                        request.setMethod(tokens.get(++i).toUpperCase());
                    }
                    break;
                case "-H":
                case "--header":
                    if (i + 1 < tokens.size()) {
                        String header = tokens.get(++i);
                        parseHeader(header, headers);
                    }
                    break;
                case "-d":
                case "--data":
                case "--data-raw":
                case "--data-binary":
                    if (i + 1 < tokens.size()) {
                        bodyData = tokens.get(++i);
                        // 如果有 -d 且没指定 -X, 默认 POST
                        if ("GET".equals(request.getMethod())) {
                            request.setMethod("POST");
                        }
                    }
                    break;
                case "-b":
                case "--cookie":
                    if (i + 1 < tokens.size()) {
                        String cookie = tokens.get(++i);
                        headers.add(new KeyValue("Cookie", cookie, "", true));
                    }
                    break;
                case "-u":
                case "--user":
                    if (i + 1 < tokens.size()) {
                        String userPass = tokens.get(++i);
                        // Basic Auth
                        AuthConfig auth = new AuthConfig();
                        auth.setType("basic");
                        int colon = userPass.indexOf(':');
                        if (colon >= 0) {
                            auth.setBasicUsername(userPass.substring(0, colon));
                            auth.setBasicPassword(userPass.substring(colon + 1));
                        } else {
                            auth.setBasicUsername(userPass);
                        }
                        request.setAuth(auth);
                    }
                    break;
                case "--compressed":
                case "-k":
                case "--insecure":
                case "-L":
                case "--location":
                    // 忽略这些标志
                    break;
                case "-o":
                case "--output":
                    i++; // 跳过输出文件参数
                    break;
                default:
                    // 不是选项的 token 可能是 URL
                    if (!token.startsWith("-") && (token.startsWith("http://") ||
                        token.startsWith("https://") || token.startsWith("'http") ||
                        token.startsWith("\"http"))) {
                        String url = token.replaceAll("^['\"]", "").replaceAll("['\"]$", "");
                        request.setUrl(url);
                    }
                    break;
            }
        }

        // 设置 body
        if (bodyData != null) {
            request.setBodyType("raw");
            request.setBodyContent(bodyData);
            // 检查 Content-Type 判断 raw 类型
            boolean hasJson = false;
            for (KeyValue h : headers) {
                if ("Content-Type".equalsIgnoreCase(h.getKey()) &&
                    h.getValue().contains("json")) {
                    hasJson = true;
                    break;
                }
            }
            if (hasJson) {
                request.setRawContentType("application/json");
            } else {
                request.setRawContentType("text/plain");
            }
        }

        request.setHeaders(headers);
        request.setParams(params);
        return request;
    }

    /**
     * 将命令行文本分割为 tokens, 引号内的内容作为一个整体。
     */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 解析单个 Header 字符串 "Key: Value"。
     */
    private static void parseHeader(String header, List<KeyValue> headers) {
        int colon = header.indexOf(':');
        if (colon >= 0) {
            String key = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            headers.add(new KeyValue(key, value, "", true));
        }
    }
}
