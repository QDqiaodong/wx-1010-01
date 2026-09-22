package com.icepark.enums;

/**
 * 送检单上的操作类型。每次操作都追加一条 inspection_event 留痕，
 * 历史只追加、不改写、不删除，后一次复检不会覆盖之前的维修/退回痕迹。
 */
public enum InspectionAction {
    SUBMIT("提交送检"),
    RETURN_MATERIALS("退回补充材料"),
    RESUBMIT("材料补齐重新提交"),
    SUBMIT_REINSPECTION("维修完成提交复检"),
    REINSPECTION_PASS("复检通过"),
    REINSPECTION_FAIL("复检不通过"),
    SCRAP("判定报废");

    private final String label;

    InspectionAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
