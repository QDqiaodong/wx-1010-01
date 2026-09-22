package com.icepark.enums;

/**
 * 一条发装流水的生命周期状态。
 * ISSUED：发出、游客使用中；RETURNED：游客正常归还；AUTO_CLOSED：场次结束时系统兜底收回；
 * TRANSFERRED_PENDING：游客使用期间器材被送检，经"转入待处理"先从游客处收回、
 *                     随后进入维修流程。与正常归还区分开，便于事后核查为什么器材没回架。
 */
public enum DispatchStatus {
    ISSUED("已领用"),
    RETURNED("已归还"),
    AUTO_CLOSED("结束兜底收回"),
    TRANSFERRED_PENDING("转入待处理");

    private final String label;

    DispatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
