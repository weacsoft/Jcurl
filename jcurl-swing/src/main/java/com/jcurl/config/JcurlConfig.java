package com.jcurl.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Jcurl SpringBoot 配置类。
 * <p>
 * 架构: 移除 JPA/Hibernate/SQLite, 改为纯 JSON 文件存储。
 * 数据目录结构 .api-client/:
 *   collections/    — 每个集合一个 .json 文件
 *   environments/   — 每个环境一个 .json 文件
 *   plugins/        — 插件目录
 *   reports/        — 性能测试报告
 *   globals.json    — 全局变量
 *   history.json    — 历史记录
 *   settings.json   — 用户设置
 */
@SpringBootApplication(scanBasePackages = "com.jcurl")
public class JcurlConfig {

    @Value("${jcurl.data-dir}")
    private String dataDir;

    /**
     * Jackson ObjectMapper — 配置为格式化输出、容错反序列化。
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 数据根目录 Path — 供 Store 层注入使用。
     */
    @Bean("dataDirPath")
    public Path dataDirPath() {
        return Paths.get(dataDir);
    }
}
