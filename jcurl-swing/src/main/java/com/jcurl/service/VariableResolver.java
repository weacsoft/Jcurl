package com.jcurl.service;

import com.jcurl.model.KeyValue;
import com.jcurl.model.Variable;
import com.jcurl.model.dto.RequestConfig;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量解析器 — v2 架构。
 * <p>
 * 支持 4 级变量作用域 (从高到低):
 * 1. 本地变量 (Local) — 通过 resolve 方法临时注入
 * 2. 环境变量 (Environment) — 通过 EnvironmentService 获取
 * 3. 集合变量 (Collection) — 通过 resolve(collectionVariables, ...) 传入
 * 4. 全局变量 (Global) — 通过 EnvironmentService 获取
 * <p>
 * 动态变量函数 ({{$funcName}} 语法):
 * - {{$timestamp}} — 当前 Unix 时间戳 (秒)
 * - {{$uuid}} — 随机 UUID
 * - {{$randomInt}} — 随机整数
 * - {{$datetime}} — 当前 ISO 日期时间
 * <p>
 * 普通变量语法: {{variableName}}
 */
@Service
public class VariableResolver {

    /** 匹配 {{key}} / {{ key }} 形式的变量占位符 */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    /** 匹配 {{$func}} 形式的动态变量函数 */
    private static final Pattern DYNAMIC_PATTERN = Pattern.compile("\\{\\{\\$(\\w+)\\}\\}");

    private final EnvironmentService environmentService;

    public VariableResolver(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    /**
     * 解析字符串中的 {{key}} 占位符, 用有效变量值替换。
     * 不含集合变量和本地变量。
     *
     * @param raw 原始字符串, 可为 null
     * @return 替换后的字符串
     */
    public String resolve(String raw) {
        return resolve(raw, null, null);
    }

    /**
     * 解析字符串, 支持全部 4 级变量作用域。
     *
     * @param raw               原始字符串, 可为 null
     * @param collectionVariables 集合变量列表, 可为 null
     * @param localVariables    本地变量 Map, 可为 null
     * @return 替换后的字符串
     */
    public String resolve(String raw, List<Variable> collectionVariables, Map<String, String> localVariables) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }

        // 合并变量: 全局 < 集合 < 环境 < 本地
        // collectionVariables 为 null 时调用无参版本, 与有参版本传 null 在生产环境等价
        // (getEffectiveVariables(null) 会跳过集合变量合并), 同时便于单元测试对
        // getEffectiveVariables() 的 mock 桩生效。
        Map<String, String> variables = collectionVariables == null
                ? environmentService.getEffectiveVariables()
                : environmentService.getEffectiveVariables(collectionVariables);
        if (localVariables != null) {
            variables.putAll(localVariables);
        }

        // 先解析动态变量函数
        String result = resolveDynamic(raw);

        // 再解析普通变量
        Matcher matcher = VARIABLE_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.get(key);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                // 未定义变量, 保留原占位符
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析动态变量函数 {{$funcName}}。
     */
    private String resolveDynamic(String raw) {
        Matcher matcher = DYNAMIC_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String func = matcher.group(1);
            String value = evaluateDynamicFunction(func);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 执行动态变量函数, 返回结果字符串。
     * 未知函数返回原占位符文本。
     */
    private String evaluateDynamicFunction(String func) {
        switch (func) {
            case "timestamp":
                return String.valueOf(Instant.now().getEpochSecond());
            case "uuid":
                return UUID.randomUUID().toString();
            case "randomInt":
                return String.valueOf(ThreadLocalRandom.current().nextInt(0, 10001));
            case "datetime":
                return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(java.time.LocalDateTime.now());
            default:
                return "{{$" + func + "}}";
        }
    }

    /**
     * 对 KeyValue 列表中每个 enabled=true 的 value 执行变量解析。
     */
    public List<KeyValue> resolveKeyValues(List<KeyValue> kvs) {
        return resolveKeyValues(kvs, null, null);
    }

    /**
     * 对 KeyValue 列表执行变量解析, 支持全部变量作用域。
     */
    public List<KeyValue> resolveKeyValues(List<KeyValue> kvs, List<Variable> collectionVariables,
                                           Map<String, String> localVariables) {
        if (kvs == null) {
            return null;
        }
        List<KeyValue> result = new ArrayList<>();
        for (KeyValue kv : kvs) {
            KeyValue copy = new KeyValue(kv.getKey(), kv.getValue(), kv.getDescription(), kv.isEnabled());
            if (copy.isEnabled()) {
                copy.setValue(resolve(copy.getValue(), collectionVariables, localVariables));
            }
            result.add(copy);
        }
        return result;
    }

    /**
     * 对 Map 中每个 value 执行变量解析。
     * 不含集合变量和本地变量。
     *
     * @param input 输入 Map, 可为 null
     * @return 替换后的新 Map; input 为 null 时返回 null
     */
    public Map<String, String> resolveMap(Map<String, String> input) {
        return resolveMap(input, null, null);
    }

    /**
     * 对 Map 执行变量解析, 支持全部变量作用域。
     *
     * @param input               输入 Map, 可为 null
     * @param collectionVariables 集合变量列表, 可为 null
     * @param localVariables      本地变量 Map, 可为 null
     * @return 替换后的新 Map; input 为 null 时返回 null
     */
    public Map<String, String> resolveMap(Map<String, String> input, List<Variable> collectionVariables,
                                          Map<String, String> localVariables) {
        if (input == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            result.put(entry.getKey(), resolve(entry.getValue(), collectionVariables, localVariables));
        }
        return result;
    }

    /**
     * 获取所有可用变量名 (用于自动补全)。
     * 包含全局变量、环境变量和动态变量函数名。
     *
     * @return 变量名列表
     */
    public List<String> getAvailableVariableNames() {
        List<String> names = new ArrayList<>();
        Map<String, String> effective = environmentService.getEffectiveVariables();
        names.addAll(effective.keySet());
        names.add("$timestamp");
        names.add("$uuid");
        names.add("$randomInt");
        names.add("$datetime");
        return names;
    }

    /**
     * 获取指定集合下所有可用变量名 (包含集合变量)。
     *
     * @param collectionVariables 集合变量列表
     * @return 变量名列表
     */
    public List<String> getAvailableVariableNames(List<Variable> collectionVariables) {
        List<String> names = new ArrayList<>();
        Map<String, String> effective = environmentService.getEffectiveVariables(collectionVariables);
        names.addAll(effective.keySet());
        names.add("$timestamp");
        names.add("$uuid");
        names.add("$randomInt");
        names.add("$datetime");
        return names;
    }
}
