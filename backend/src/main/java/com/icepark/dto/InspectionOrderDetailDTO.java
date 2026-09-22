package com.icepark.dto;

import lombok.Data;

import java.util.List;

/**
 * 送检单详情：单据当前状态 + 按时间顺序的完整操作痕迹 + 器材当前资产状态。
 * 历史只追加，前端按 events 渲染"送检 → 退回 → 维修 → 复检 → 放行/报废"全链路。
 */
@Data
public class InspectionOrderDetailDTO {

    private InspectionOrderDTO order;

    private String equipmentStatus;
    private String equipmentStatusLabel;

    private List<InspectionEventDTO> events;
}
