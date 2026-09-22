package com.icepark.dto;

import com.icepark.enums.InspectionAction;
import com.icepark.enums.InspectionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条送检操作痕迹（只追加）。
 */
@Data
public class InspectionEventDTO {

    private Long id;
    private Long inspectionOrderId;
    private Long equipmentId;

    private InspectionAction action;
    private String actionLabel;

    private InspectionStatus fromStatus;
    private String fromStatusLabel;
    private InspectionStatus toStatus;
    private String toStatusLabel;

    private String operator;
    private String note;
    private LocalDateTime eventTime;
}
