package com.icepark.entity;

import com.icepark.enums.InspectionActionType;
import com.icepark.enums.InspectionStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 送检单操作痕迹：送检、归还、转入待处理、退回补充材料、补充后重提、
 * 提交复检、复检通过/不通过、报废，每次状态变化都追加一条，只增不改。
 */
@Entity
@Table(name = "inspection_action_log",
        indexes = {
                @Index(name = "idx_action_order_id", columnList = "order_id"),
                @Index(name = "idx_action_equipment_id", columnList = "equipment_id"),
                @Index(name = "idx_action_time", columnList = "action_time")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InspectionActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private InspectionActionType actionType;

    /** 操作前状态（创建时为 null） */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private InspectionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private InspectionStatus toStatus;

    /** 操作人（发现人/归还人/维修人员/复检人员） */
    @Column(name = "operator", nullable = false, length = 100)
    private String operator;

    /** 备注：退回原因、补充材料、复检结论/不通过原因、报废原因等 */
    @Column(name = "remark", length = 1000)
    private String remark;

    /** 若关联发装流水（归还/转入待处理/兜底），记录流水ID */
    @Column(name = "dispatch_record_id")
    private Long dispatchRecordId;

    @Column(name = "action_time", nullable = false)
    private LocalDateTime actionTime = LocalDateTime.now();
}
