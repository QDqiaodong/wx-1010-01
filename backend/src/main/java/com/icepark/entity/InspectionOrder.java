package com.icepark.entity;

import com.icepark.enums.InspectionStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 器材送检单。一张单 = 一次"现场发现异常 → 送检 → 维修 → 复检 → 重新可用/报废"的完整闭环。
 *
 * 历史不被覆盖：复检不通过时本单按终态关闭（SCRAPPED 或保留为不通过），重新送检另开新单；
 * 单据创建后问题描述、发现人不可修改，补充材料以操作痕迹追加。
 *
 * 并发互斥：openKey 在单据未关闭期间写器材ID（"inspect:{equipmentId}"），
 * 关闭后置 NULL 并配唯一索引，同一件器材数据库层面不可能同时存在两张未关闭送检单。
 */
@Entity
@Table(name = "inspection_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inspection_open",
                columnNames = "open_key"),
        indexes = {
                @Index(name = "idx_inspection_equipment_id", columnList = "equipment_id"),
                @Index(name = "idx_inspection_status", columnList = "status"),
                @Index(name = "idx_inspection_create_time", columnList = "create_time")
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

    /** 现场登记的异常问题描述，创建后不可修改 */
    @Column(name = "problem_description", nullable = false, length = 1000)
    private String problemDescription;

    /** 发现人（现场工作人员） */
    @Column(name = "reporter", nullable = false, length = 100)
    private String reporter;

    @Column(name = "report_time", nullable = false)
    private LocalDateTime reportTime;

    /** 当前维修/复检处理人（最近一次操作的处理人） */
    @Column(name = "handler", length = 100)
    private String handler;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InspectionStatus status = InspectionStatus.SUBMITTED;

    /** 若送检时器材仍在游客手上：关联的未归还发装流水，归还/转入待处理后仍保留用于追溯 */
    @Column(name = "dispatch_record_id")
    private Long dispatchRecordId;

    @Column(name = "session_id")
    private Long sessionId;

    /** 最新结论（复检通过说明 / 复检不通过原因 / 报废原因），历史结论在 action_log 中逐条保留 */
    @Column(name = "conclusion", length = 1000)
    private String conclusion;

    @Column(name = "close_time")
    private LocalDateTime closeTime;

    @Column(name = "create_time")
    private LocalDateTime createTime = LocalDateTime.now();

    @Column(name = "update_time")
    private LocalDateTime updateTime = LocalDateTime.now();

    /** 未关闭期间为 "inspect:{equipmentId}"，关闭（通过/报废）后为 NULL，配合唯一索引互斥 */
    @Column(name = "open_key", length = 64)
    private String openKey;

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
