package com.icepark.enums;

/**
 * 送检单生命周期状态。历史送检单永久保留，复检不通过/重新送检都会产生新单据，
 * 任何复检结论都不会覆盖上一张送检单。
 *
 * 未关闭状态（open）：PENDING_RETURN / SUBMITTED / INFO_NEEDED / REINSPECTING，
 * 同一件器材任何时刻至多存在一张未关闭送检单（DB 唯一索引 + 应用锁双重保证）。
 * 终态：PASSED（复检通过，器材回到可用池）、SCRAPPED（判定报废）。
 */
public enum InspectionStatus {
    /** 已登记送检，但器材还在游客手上（有未归还发装流水），等待正常归还或转入待处理 */
    PENDING_RETURN("待归还", true),
    /** 维修台待处理：器材已在库，等待维修人员处理 */
    SUBMITTED("待维修", true),
    /** 维修退回补充材料，等待发现人补充后重新提交 */
    INFO_NEEDED("待补充材料", true),
    /** 已提交复检，等待复检结论 */
    REINSPECTING("复检中", true),
    /** 终态：复检通过，器材重新回到可用池 */
    PASSED("复检通过", false),
    /** 终态：判定报废，历史保留、从可绑定清单消失 */
    SCRAPPED("已报废", false);

    private final String label;
    private final boolean open;

    InspectionStatus(String label, boolean open) {
        this.label = label;
        this.open = open;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOpen() {
        return open;
    }
}
