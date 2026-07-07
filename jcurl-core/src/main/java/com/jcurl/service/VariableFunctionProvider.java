package com.jcurl.service;

import com.jcurl.plugin.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态变量函数提供者 — 解析 {{$funcName}} 或 {{$funcName args}} 格式的动态变量。
 * <p>
 * 内置函数:
 * <ul>
 *   <li>{{$timestamp}} — 当前 Unix 时间戳(秒)</li>
 *   <li>{{$uuid}} — 随机 UUID</li>
 *   <li>{{$randomInt}} — 随机整数(0-1000)</li>
 *   <li>{{$datetime}} — 当前 ISO 日期时间</li>
 * </ul>
 * <p>
 * 可被插件扩展:通过 {@link com.jcurl.plugin.extension.VariableFunctionExtension} 扩展点
 * 注册自定义函数,内置函数不命中时自动委托给插件查询。
 */
@Component
public class VariableFunctionProvider {

    /** 动态函数正则:{{$funcName}} 或 {{$funcName arg1 arg2}} */
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\{\\{\\$([a-zA-Z]\\w*)(?:\\s+([^}]*))?\\}\\}");

    /** 内置函数映射 */
    private final Map<String, java.util.function.Function<String[], String>> builtinFunctions;

    /** 插件服务(@Lazy 避免循环依赖: PluginService → VariableResolver → 本类 → PluginService) */
    @Autowired
    @Lazy
    private PluginService pluginService;

    public VariableFunctionProvider() {
        this.builtinFunctions = Map.of(
                "timestamp", args -> String.valueOf(Instant.now().getEpochSecond()),
                "uuid", args -> UUID.randomUUID().toString(),
                "randomInt", args -> {
                    int min = args.length > 0 ? parseIntSafe(args[0], 0) : 0;
                    int max = args.length > 1 ? parseIntSafe(args[1], 1000) : 1000;
                    return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
                },
                "datetime", args -> DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        );
    }

    /**
     * 解析字符串中的所有动态函数占位符。
     *
     * @param input 原始字符串
     * @return 替换后的字符串
     */
    public String resolve(String input) {
        if (input == null || !input.contains("{{$")) {
            return input;
        }

        Matcher matcher = FUNCTION_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String funcName = matcher.group(1);
            String argsStr = matcher.group(2);
            String[] args = argsStr != null ? argsStr.trim().split("\\s+") : new String[0];

            String value = evaluate(funcName, args, argsStr);
            matcher.appendReplacement(result, value != null ? Matcher.quoteReplacement(value) : matcher.group());
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 评估单个函数:先查内置,再查插件 */
    private String evaluate(String funcName, String[] args, String rawArgs) {
        // 1. 内置函数
        java.util.function.Function<String[], String> func = builtinFunctions.get(funcName);
        if (func != null) {
            try {
                return func.apply(args);
            } catch (Exception e) {
                return null;
            }
        }
        // 2. 插件自定义函数
        if (pluginService != null) {
            try {
                return pluginService.resolveCustomFunction(funcName, rawArgs != null ? rawArgs.trim() : "");
            } catch (Exception e) {
                return null;
            }
        }
        return null; // 未知函数,保留原样
    }

    /** 判断字符串是否包含动态函数 */
    public boolean containsFunction(String input) {
        return input != null && input.contains("{{$");
    }

    private int parseIntSafe(String s, int defaultValue) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
