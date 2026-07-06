package com.jcurl2.model;

import com.jcurl2.model.component.Variable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境 — 一组命名变量集合,如"开发环境""测试环境""生产环境"。
 * <p>
 * 每个环境对应 environments/ 目录下的一个 JSON 文件。切换环境时自动切换变量值。
 */
public class Environment {

    private String id;
    private String name;
    private List<Variable> variables = new ArrayList<>();
    private LocalDateTime createdAt;

    public Environment() {}

    public Environment(String id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Variable> getVariables() {
        if (variables == null) variables = new ArrayList<>();
        return variables;
    }
    public void setVariables(List<Variable> variables) { this.variables = variables; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
