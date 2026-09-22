package com.icepark.dto;

import com.icepark.enums.InspectionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 送检单列表/摘要。列表页与器材列表的"当前送检单"都用它。
 */
@Data
public class InspectionOrderDTO {

    private Long id;
    private Long equipmentId;
    private String equipmentCode;
    private String equipmentName;

    private String reporter;
    private String problemDescription;
    private LocalDateTime submitTime;

    private InspectionStatus status;
    private String statusLabel;

    private String handler;
    private String conclusion;
    private LocalDateTime updateTime;

    private Integer eventCount;
}
