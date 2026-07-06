package com.jcurl2.model;

import java.util.ArrayList;
import java.util.List;

/**
 * history.json 的顶层结构 — 包含历史记录列表与上限配置。
 */
public class HistoryStore {

    private List<HistoryRecord> records = new ArrayList<>();
    private int limit = 500;

    public HistoryStore() {}

    public List<HistoryRecord> getRecords() {
        if (records == null) records = new ArrayList<>();
        return records;
    }

    public void setRecords(List<HistoryRecord> records) {
        this.records = records;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
