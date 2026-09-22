package com.icepark.enums;

/**
 * 一条发装流水的生命周期状态。
 * ISSUED：发出、游客使用中；RETURNED：游客正常归还；AUTO_CLOSED：场次结束时系统兜底收回；
 * PENDING_TRANSFER：游客未归还、器材随送检单转入待处理（悬空消解，不再阻塞归还/维修链路）。
 */
public enum DispatchStatus {
    ISSUED("已领用"),
    RETURNED("已归还"),
    AUTO_CLOSED("结束兜底收回"),
    PENDING_TRANSFER("转入待处理");

    private final String label;

    DispatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 流水已终结：已归还 / 兜底收回 / 转入待处理 */
    public boolean isClosed() {
        return this != ISSUED;
    }
}
