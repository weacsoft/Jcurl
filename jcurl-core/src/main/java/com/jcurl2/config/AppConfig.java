package com.jcurl2.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * SpringBoot 应用配置类 — 同时作为容器入口与公共 Bean 定义。
 * <p>
 * 使用 @SpringBootApplication 注解,使测试可通过 @SpringBootTest 自动发现配置。
 * application.yml 中 {@code spring.main.web-application-type: none} 确保非 Web 模式。
 */
@SpringBootApplication(scanBasePackages = "com.jcurl2")
public class AppConfig {

    /**
     * Jackson ObjectMapper — 全局共享,配置日期时间支持与美化输出。
     * <p>
     * 用于 JSON 文件读写(集合、环境、历史等)与 HTTP 响应解析。
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /**
     * 应用配置属性绑定(对应 application.yml 中 jcurl2.* 前缀)。
     */
    @Bean
    @ConfigurationProperties(prefix = "jcurl2")
    public AppProperties appProperties() {
        return new AppProperties();
    }
}
