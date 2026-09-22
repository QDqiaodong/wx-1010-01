package com.icepark.enums;

/**
 * 场次-器材绑定的现场发装状态。
 * AVAILABLE：在架可领；ISSUED：已发给某位游客、尚未归还；
 * PENDING：该器材已送检（待归还/维修/复检/报废链路中），本场次行冻结，禁止发装。
 */
public enum BindDispatchStatus {
    AVAILABLE("在架"),
    ISSUED("已领用"),
    PENDING("送检冻结");

    private final String label;

    BindDispatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
