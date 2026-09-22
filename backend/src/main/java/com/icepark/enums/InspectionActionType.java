package com.icepark.enums;

/**
 * 送检链路上的操作类型。每次状态变化都写一条操作痕迹（inspection_action_log），
 * 与发装流水一起构成可查询的完整审计链。
 */
public enum InspectionActionType {
    CREATE("登记送检"),
    TRANSFER_PENDING("游客未归还转入待处理"),
    RETURN_WHILE_PENDING("送检期间游客归还"),
    SESSION_AUTO_CLOSE("场次结束兜底收回"),
    RESUBMIT("补充材料后重新提交"),
    REQUEST_INFO("退回补充材料"),
    SUBMIT_REINSPECTION("提交复检"),
    REINSPECTION_PASS("复检通过"),
    REINSPECTION_FAIL("复检不通过"),
    SCRAP("判定报废");

    private final String label;

    InspectionActionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
