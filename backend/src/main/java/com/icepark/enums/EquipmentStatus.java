package com.icepark.enums;

/**
 * 器材资产级状态。
 * AVAILABLE：可用，可被新场次绑定/发装；
 * IN_USE：已绑定场次（在架或已发给游客）；
 * INSPECTION：送检中，存在未关闭的送检单，不能再被新场次绑定或发装；
 * SCRAPPED：已报废，保留全部历史，但从所有可绑定/可发装清单中消失。
 */
public enum EquipmentStatus {
    AVAILABLE("可用"),
    IN_USE("使用中"),
    MAINTENANCE("维护中"),
    INSPECTION("送检中"),
    SCRAPPED("已报废");

    private final String label;

    EquipmentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 送检单未关闭期间的状态：不能被新场次绑定或发装 */
    public boolean isUnavailableForBinding() {
        return this == INSPECTION || this == SCRAPPED || this == MAINTENANCE;
    }
}
