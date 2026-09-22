package com.icepark.entity;

import com.icepark.enums.InspectionStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 器材送检单。一张单据 = 一次完整的"送检 → 维修（可反复退回补材料）→ 复检 → 放行/报废"流程。
 *
 * 同一件器材任何时刻最多只能有一张未关闭单据：
 * 未关闭期间 openKey 写器材ID（"inspection:{equipmentId}"），终态置 NULL，
 * 配合唯一索引在数据库层兜底重复送检，即使两个请求同时穿过应用层也只可能插入一张。
 *
 * 历史不覆盖：退回/复检等只更新单据当前状态并向 inspection_event 追加痕迹，
 * 复检通过后单据保留为 CLOSED_PASSED，同一件器材下次送检必须新建单据。
 */
@Entity
@Table(name = "inspection_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inspection_open",
                columnNames = "open_key"),
        indexes = {
                @Index(name = "idx_inspection_equipment_id", columnList = "equipment_id"),
                @Index(name = "idx_inspection_status", columnList = "status"),
                @Index(name = "idx_inspection_submit_time", columnList = "submit_time")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InspectionOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** 现场发现问题的工作人员 */
    @Column(name = "reporter", nullable = false, length = 100)
    private String reporter;

    /** 发现的异常问题描述 */
    @Column(name = "problem_description", nullable = false, length = 1000)
    private String problemDescription;

    @Column(name = "submit_time", nullable = false)
    private LocalDateTime submitTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InspectionStatus status = InspectionStatus.SUBMITTED;

    /** 当前维修/复检处理人（每次操作都会同时写一条事件留痕） */
    @Column(name = "handler", length = 100)
    private String handler;

    /** 当前处理结论（通过/不通过原因、报废理由等）；历史结论见 inspection_event */
    @Column(name = "conclusion", length = 1000)
    private String conclusion;

    @Column(name = "update_time")
    private LocalDateTime updateTime = LocalDateTime.now();

    /** 未关闭期间为 "inspection:{equipmentId}"，关闭（通过/报废）后置 NULL。配合唯一索引互斥。 */
    @Column(name = "open_key", length = 64)
    private String openKey;

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
