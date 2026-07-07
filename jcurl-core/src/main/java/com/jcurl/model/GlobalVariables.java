package com.jcurl.model;

import com.jcurl.plugin.model.component.Variable;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局变量 — 跨所有集合、所有环境生效的变量。
 * <p>
 * 存储在 globals.json 中。
 */
public class GlobalVariables {

    private List<Variable> variables = new ArrayList<>();

    public GlobalVariables() {}

    public List<Variable> getVariables() {
        if (variables == null) variables = new ArrayList<>();
        return variables;
    }

    public void setVariables(List<Variable> variables) {
        this.variables = variables;
    }
}
