package com.icepark.enums;

/**
 * 场次-器材绑定的现场发装状态。
 * AVAILABLE：在架可领；ISSUED：已发给某位游客、尚未归还；
 * QUARANTINED：器材已送检（含游客使用中转入待处理），本绑定行被隔离，不能发装，
 *             复检通过后由系统批量复位在架；SCRAPPED：器材在送检中被判报废，绑定行仅留档。
 */
public enum BindDispatchStatus {
    AVAILABLE("在架"),
    ISSUED("已领用"),
    QUARANTINED("送检隔离"),
    SCRAPPED("已报废留档");

    private final String label;

    BindDispatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
