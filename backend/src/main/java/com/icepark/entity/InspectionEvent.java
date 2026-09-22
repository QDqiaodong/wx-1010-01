package com.icepark.entity;

import com.icepark.enums.InspectionAction;
import com.icepark.enums.InspectionStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 送检单操作痕迹（只追加，不修改、不删除）。
 * 退回补充材料、提交复检、复检通过/不通过、报废每一步各留一条，
 * 后一次复检不会覆盖之前的记录，验收/审计按 inspection_order_id 顺序回溯完整链路。
 */
@Entity
@Table(name = "inspection_event",
        indexes = {
                @Index(name = "idx_inspection_event_order_id", columnList = "inspection_order_id"),
                @Index(name = "idx_inspection_event_equipment_id", columnList = "equipment_id"),
                @Index(name = "idx_inspection_event_time", columnList = "event_time")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InspectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspection_order_id", nullable = false)
    private Long inspectionOrderId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private InspectionAction action;

    /** 操作前状态（提交送检时为 null） */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private InspectionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private InspectionStatus toStatus;

    @Column(name = "operator", nullable = false, length = 100)
    private String operator;

    /** 处理说明/结论/补充材料说明/复检意见 */
    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;
}
