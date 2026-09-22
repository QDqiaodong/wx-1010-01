package com.icepark.dto;

import com.icepark.enums.InspectionActionType;
import com.icepark.enums.InspectionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 送检单操作痕迹（可查询的审计条目，只增不改） */
@Data
public class InspectionActionLogDTO {

    private Long id;
    private Long orderId;
    private Long equipmentId;
    private InspectionActionType actionType;
    private String actionTypeLabel;
    private InspectionStatus fromStatus;
    private String fromStatusLabel;
    private InspectionStatus toStatus;
    private String toStatusLabel;
    private String operator;
    private String remark;
    private Long dispatchRecordId;
    private LocalDateTime actionTime;
}
