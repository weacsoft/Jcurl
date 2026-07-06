package com.jcurl.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 环境文件 — 对应磁盘上的一个 JSON 文件 (如 environments/dev.json)。
 * <p>
 * 包含环境名称和变量列表, 变量可标记为 Secret (加密存储)。
 */
public class EnvironmentFile {

    private String id;
    private String name;
    private List<Variable> variables = new ArrayList<>();

    public EnvironmentFile() {
        this.id = UUID.randomUUID().toString();
    }

    public EnvironmentFile(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Variable> getVariables() {
        return variables;
    }

    public void setVariables(List<Variable> variables) {
        this.variables = variables != null ? variables : new ArrayList<>();
    }
}
