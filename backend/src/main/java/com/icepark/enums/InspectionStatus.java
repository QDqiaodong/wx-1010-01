package com.icepark.enums;

/**
 * 送检单生命周期状态。状态机：
 *
 * 提交            退回补充材料        重新提交（材料补齐）
 * SUBMITTED ───────────────▶ MATERIAL_NEEDED ──────────────▶ SUBMITTED
 *     │                          │
 *     │ 提交复检                  │ 报废
 *     ▼                          ▼
 * REINSPECTING ──复检不通过──▶ SUBMITTED
 *     │
 *     ├──复检通过──▶ CLOSED_PASSED（器材回可用池）
 *     └──报废──────▶ CLOSED_SCRAPPED（器材报废、退出可绑定清单）
 *
 * 仅 SUBMITTED 可报废：任何中间环节想报废，先由维修侧退回/提交后再执行。
 * CLOSED_* 为终态，终态单据的历史不允许再被修改；再次送检必须新建单据。
 */
public enum InspectionStatus {
    SUBMITTED("待维修"),
    MATERIAL_NEEDED("待补充材料"),
    REINSPECTING("复检中"),
    CLOSED_PASSED("复检通过关闭"),
    CLOSED_SCRAPPED("报废关闭");

    private final String label;

    InspectionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOpen() {
        return this != CLOSED_PASSED && this != CLOSED_SCRAPPED;
    }
}
