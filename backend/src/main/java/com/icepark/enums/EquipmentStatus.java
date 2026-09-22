package com.icepark.enums;

/**
 * 器材资产级状态。
 * AVAILABLE：可用池，可被新场次绑定；
 * IN_USE：已绑定到场次（绑定即 IN_USE，解绑/兜底后复位）；
 * MAINTENANCE：人工维护（历史保留）；
 * INSPECTION：已开送检单、处于送检/维修/复检流程中，不能绑定、不能发装；
 * SCRAPPED：经送检流程判定报废，永久退出可用池与可绑定清单（历史保留）。
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
}
