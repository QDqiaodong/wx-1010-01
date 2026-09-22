package com.icepark.dto;

import com.icepark.enums.InspectionStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 送检单：器材、发现人、问题描述、送检时间、处理人、结论及完整操作痕迹 */
@Data
public class InspectionOrderDTO {

    private Long id;
    private Long equipmentId;
    private String equipmentCode;
    private String equipmentName;
    private String category;

    private String problemDescription;
    private String reporter;
    private LocalDateTime reportTime;

    private String handler;
    private InspectionStatus status;
    private String statusLabel;
    private boolean open;

    /** 送检时器材在游客手上时关联的发装流水 */
    private Long dispatchRecordId;
    private Long sessionId;

    private String conclusion;
    private LocalDateTime closeTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 完整操作痕迹（时间正序），复检不覆盖历史 */
    private List<InspectionActionLogDTO> actionLogs;
}
