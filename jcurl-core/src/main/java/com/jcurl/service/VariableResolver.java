package com.jcurl.service;

import com.jcurl.model.Collection;
import com.jcurl.model.Environment;
import com.jcurl.model.GlobalVariables;
import com.jcurl.plugin.model.component.Variable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量解析器 — 解析字符串中的 {{var}} 与 {{$func}} 占位符。
 * <p>
 * 解析规则:
 * <ol>
 *   <li>先解析动态函数 {{$func}}(由 VariableFunctionProvider 处理)</li>
 *   <li>再解析普通变量 {{var}},按 4 级优先级查找:Local > Environment > Collection > Global</li>
 *   <li>Secret 变量的值在存储时 Base64 编码,解析时自动解码</li>
 *   <li>未命中的变量保留原样 {{var}}</li>
 * </ol>
 * <p>
 * 可在 URL、Query Params 值、Header 值、Body 文本(Raw)、认证配置值中使用。
 */
@Service
public class VariableResolver {

    /** 变量占位符正则:{{varName}},不匹配 {{$func}} */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^$}][^}]*)\\}\\}");

    private final VariableFunctionProvider functionProvider;

    public VariableResolver(VariableFunctionProvider functionProvider) {
        this.functionProvider = functionProvider;
    }

    /**
     * 解析字符串中的所有变量占位符。
     *
     * @param raw   原始字符串,可能含 {{var}} 和 {{$func}}
     * @param scope 变量作用域
     * @return 解析后的字符串,未命中的变量保留原样
     */
    public String resolve(String raw, VariableScope scope) {
        if (raw == null || !raw.contains("{{")) {
            return raw;
        }

        // 1. 先解析动态函数 {{$func}}
        String result = functionProvider.resolve(raw);

        // 2. 再解析普通变量 {{var}}
        if (!result.contains("{{")) {
            return result;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String value = lookupVariable(varName, scope);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                // 未命中,保留原样
                matcher.appendReplacement(sb, matcher.group());
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 按优先级查找变量:Local > Environment > Collection > Global。
     * Secret 变量的值 Base64 解码后返回。
     */
    private String lookupVariable(String name, VariableScope scope) {
        // 1. Local
        String localVal = scope.getLocal().get(name);
        if (localVal != null) {
            return localVal;
        }

        // 2. Environment
        if (scope.getActiveEnvironment() != null) {
            String val = findInVariables(name, scope.getActiveEnvironment().getVariables());
            if (val != null) return val;
        }

        // 3. Collection
        if (scope.getCollection() != null) {
            String val = findInVariables(name, scope.getCollection().getVariables());
            if (val != null) return val;
        }

        // 4. Global
        if (scope.getGlobals() != null) {
            String val = findInVariables(name, scope.getGlobals().getVariables());
            if (val != null) return val;
        }

        return null;
    }

    /** 在变量列表中查找,Secret 变量 Base64 解码 */
    private String findInVariables(String name, List<Variable> variables) {
        if (variables == null) return null;
        for (Variable v : variables) {
            if (name.equals(v.getKey())) {
                return decodeIfSecret(v);
            }
        }
        return null;
    }

    /** Secret 变量 Base64 解码 */
    private String decodeIfSecret(Variable v) {
        if (v.isSecret() && v.getValue() != null) {
            try {
                return new String(Base64.getDecoder().decode(v.getValue()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 解码失败(可能未编码),返回原值
                return v.getValue();
            }
        }
        return v.getValue();
    }

    /**
     * 收集当前作用域所有可用变量(供自动补全)。
     * <p>
     * 按优先级从低到高收集(Global → Collection → Environment → Local),
     * 同名变量高优先级覆盖低优先级。
     *
     * @param scope 变量作用域
     * @return 变量条目列表(去重后)
     */
    public List<VariableEntry> listAvailable(VariableScope scope) {
        // 用 LinkedHashMap 保持插入顺序,同名覆盖
        Map<String, VariableEntry> map = new LinkedHashMap<>();

        // Global(最低优先级)
        if (scope.getGlobals() != null) {
            for (Variable v : scope.getGlobals().getVariables()) {
                map.put(v.getKey(), toEntry(v, "Global"));
            }
        }

        // Collection
        if (scope.getCollection() != null) {
            for (Variable v : scope.getCollection().getVariables()) {
                map.put(v.getKey(), toEntry(v, "Collection"));
            }
        }

        // Environment
        if (scope.getActiveEnvironment() != null) {
            for (Variable v : scope.getActiveEnvironment().getVariables()) {
                map.put(v.getKey(), toEntry(v, "Environment"));
            }
        }

        // Local(最高优先级)
        scope.getLocal().forEach((key, value) ->
                map.put(key, new VariableEntry(key, value, "Local", false)));

        return new ArrayList<>(map.values());
    }

    private VariableEntry toEntry(Variable v, String scopeName) {
        String displayValue = v.isSecret() ? "******" : v.getValue();
        return new VariableEntry(v.getKey(), displayValue, scopeName, v.isSecret());
    }

    /**
     * 对 Secret 变量值进行 Base64 编码(存储时调用)。
     */
    public static String encodeSecret(String plainValue) {
        if (plainValue == null) return null;
        return Base64.getEncoder().encodeToString(plainValue.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对 Secret 变量值进行 Base64 解码(读取时调用)。
     */
    public static String decodeSecret(String encodedValue) {
        if (encodedValue == null) return null;
        try {
            return new String(Base64.getDecoder().decode(encodedValue), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encodedValue;
        }
    }
}
