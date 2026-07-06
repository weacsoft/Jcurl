package com.jpostman.service;

import com.jpostman.model.KeyValue;
import com.jpostman.model.dto.RequestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * VariableResolver 单元测试。
 * <p>
 * 使用 Mockito 模拟 {@link EnvironmentService}, 避免 Spring 容器与数据库依赖。
 * 聚焦于变量替换逻辑本身: {{key}} 匹配、多变量、空格容错、未定义保留、
 * 特殊字符 ($ / \\) 处理、Map/KeyValue 解析。
 */
@ExtendWith(MockitoExtension.class)
class VariableResolverTest {

    @Mock private EnvironmentService environmentService;
    @InjectMocks private VariableResolver resolver;

    /** 默认有效变量集合, 多数测试共用 */
    private Map<String, String> defaultVars;

    @BeforeEach
    void setUp() {
        defaultVars = new HashMap<>();
        defaultVars.put("base_url", "http://localhost:8080");
        defaultVars.put("version", "v1");
        defaultVars.put("token", "abc123");
    }

    @Test
    void shouldReturnOriginalStringWhenNoVariables() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        String result = resolver.resolve("plain text without variables");

        assertThat(result).isEqualTo("plain text without variables");
    }

    @Test
    void shouldResolveSingleVariable() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        String result = resolver.resolve("{{base_url}}");

        assertThat(result).isEqualTo("http://localhost:8080");
    }

    @Test
    void shouldResolveMultipleVariablesInOneString() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        String result = resolver.resolve("{{base_url}}/api/{{version}}/users");

        assertThat(result).isEqualTo("http://localhost:8080/api/v1/users");
    }

    @Test
    void shouldResolveVariableWithSurroundingSpaces() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        String result = resolver.resolve("{{ base_url }}/api");

        assertThat(result).isEqualTo("http://localhost:8080/api");
    }

    @Test
    void shouldKeepUndefinedVariableAsIs() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        String result = resolver.resolve("{{undefined_key}}/api");

        assertThat(result).isEqualTo("{{undefined_key}}/api");
    }

    @Test
    void shouldReturnNullWhenInputIsNull() {
        // 不 stub getEffectiveVariables, 因 resolve(null) 提前返回, 不应调用
        String result = resolver.resolve(null);

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnEmptyStringWhenInputIsEmpty() {
        // 不 stub getEffectiveVariables, 因 resolve("") 提前返回, 不应调用
        String result = resolver.resolve("");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldResolveMapValues() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        Map<String, String> input = new HashMap<>();
        input.put("url", "{{base_url}}/api");
        input.put("version", "{{version}}");

        Map<String, String> result = resolver.resolveMap(input);

        assertThat(result).containsEntry("url", "http://localhost:8080/api");
        assertThat(result).containsEntry("version", "v1");
    }

    @Test
    void shouldReturnNullWhenResolveMapInputIsNull() {
        assertThat(resolver.resolveMap(null)).isNull();
    }

    @Test
    void shouldResolveKeyValuesOnlyForEnabled() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        List<KeyValue> kvs = Arrays.asList(
                new KeyValue("url", "{{base_url}}/api", null, true),
                new KeyValue("ver", "{{version}}", null, false));

        List<KeyValue> result = resolver.resolveKeyValues(kvs);

        assertThat(result).hasSize(2);
        // enabled=true 的 value 被替换
        assertThat(result.get(0).getValue()).isEqualTo("http://localhost:8080/api");
        assertThat(result.get(0).isEnabled()).isTrue();
        // enabled=false 的 value 保持原样
        assertThat(result.get(1).getValue()).isEqualTo("{{version}}");
        assertThat(result.get(1).isEnabled()).isFalse();
    }

    @Test
    void shouldReturnNullWhenResolveKeyValuesInputIsNull() {
        assertThat(resolver.resolveKeyValues(null)).isNull();
    }

    @Test
    void shouldHandleSpecialCharsInVariableValue() {
        // 变量值含 $ 和 \, appendReplacement 的特殊字符, 必须经 quoteReplacement 处理
        Map<String, String> vars = new HashMap<>();
        vars.put("special", "price$100\\path");

        when(environmentService.getEffectiveVariables()).thenReturn(vars);

        String result = resolver.resolve("value={{special}}");

        assertThat(result).isEqualTo("value=price$100\\path");
    }

    @Test
    void shouldHandleEmptyEffectiveVariables() {
        when(environmentService.getEffectiveVariables()).thenReturn(Collections.emptyMap());

        String result = resolver.resolve("{{base_url}}/api");

        // 无任何变量时, 占位符保留原样
        assertThat(result).isEqualTo("{{base_url}}/api");
    }

    @Test
    void shouldNotMutateInputKeyValues() {
        when(environmentService.getEffectiveVariables()).thenReturn(defaultVars);

        KeyValue kv = new KeyValue("url", "{{base_url}}", null, true);
        List<KeyValue> input = Collections.singletonList(kv);

        List<KeyValue> result = resolver.resolveKeyValues(input);

        // 入参 KeyValue 的 value 不应被修改
        assertThat(kv.getValue()).isEqualTo("{{base_url}}");
        // 返回结果已替换
        assertThat(result.get(0).getValue()).isEqualTo("http://localhost:8080");
    }
}
