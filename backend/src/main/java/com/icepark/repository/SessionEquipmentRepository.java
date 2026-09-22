package com.icepark.repository;

import com.icepark.entity.SessionEquipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.BindDispatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionEquipmentRepository extends JpaRepository<SessionEquipment, Long> {

    List<SessionEquipment> findBySessionId(Long sessionId);

    List<SessionEquipment> findByEquipmentId(Long equipmentId);

    Optional<SessionEquipment> findBySessionIdAndEquipmentId(Long sessionId, Long equipmentId);

    List<SessionEquipment> findBySessionIdAndTargetAgeGroup(Long sessionId, AgeGroup ageGroup);

    void deleteBySessionId(Long sessionId);

    void deleteBySessionIdAndEquipmentId(Long sessionId, Long equipmentId);

    boolean existsBySessionIdAndEquipmentId(Long sessionId, Long equipmentId);

    /** 场次删除守卫：存在送检隔离/报废留档绑定行时不能删除（送检历史与场次归属必须保留） */
    @Query("SELECT COUNT(se) > 0 FROM SessionEquipment se " +
           "WHERE se.sessionId = :sessionId AND se.dispatchStatus IN :statuses")
    boolean existsBySessionIdAndDispatchStatusIn(@Param("sessionId") Long sessionId,
                                                 @Param("statuses") List<BindDispatchStatus> statuses);

    /**
     * 发装的原子条件更新（CAS）：只有当前仍在架（AVAILABLE 或历史数据 NULL）的绑定才能置为 ISSUED。
     * 两个工作人员同时点同一件器材时，数据库行锁串行化两条 UPDATE，
     * 后者影响行数为 0，据此抛出"已被领用"。不依赖应用层先读后写，也不依赖前端禁用按钮。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :issued " +
           "WHERE se.id = :id AND (se.dispatchStatus = :available OR se.dispatchStatus IS NULL)")
    int markIssuedIfAvailable(@Param("id") Long id,
                              @Param("issued") BindDispatchStatus issued,
                              @Param("available") BindDispatchStatus available);

    /** 归还：ISSUED -> AVAILABLE，同样是条件更新，返回 0 说明状态已被改动 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :available " +
           "WHERE se.id = :id AND se.dispatchStatus = :issued")
    int markAvailableIfIssued(@Param("id") Long id,
                              @Param("issued") BindDispatchStatus issued,
                              @Param("available") BindDispatchStatus available);

    /** 送检时先对该器材的全部绑定行加悲观写锁，与同场次发装/归还/结束路径互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT se FROM SessionEquipment se WHERE se.equipmentId = :equipmentId")
    List<SessionEquipment> findByEquipmentIdForUpdate(@Param("equipmentId") Long equipmentId);

    /**
     * 送检隔离：该器材所有在架/已领用绑定一律置为送检隔离（含游客使用中转入待处理的那一行）。
     * 只转换 AVAILABLE/ISSUED/历史NULL，已隔离/已报废的行不动。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :quarantined " +
           "WHERE se.equipmentId = :equipmentId " +
           "AND (se.dispatchStatus = :available OR se.dispatchStatus = :issued OR se.dispatchStatus IS NULL)")
    int markQuarantinedByEquipmentId(@Param("equipmentId") Long equipmentId,
                                     @Param("quarantined") BindDispatchStatus quarantined,
                                     @Param("available") BindDispatchStatus available,
                                     @Param("issued") BindDispatchStatus issued);

    /** 复检通过：该器材的送检隔离绑定复位在架（已结束场次的绑定也复位，保持与资产状态一致） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :available " +
           "WHERE se.equipmentId = :equipmentId AND se.dispatchStatus = :quarantined")
    int markAvailableByEquipmentIdIfQuarantined(@Param("equipmentId") Long equipmentId,
                                                @Param("available") BindDispatchStatus available,
                                                @Param("quarantined") BindDispatchStatus quarantined);

    /** 报废：送检隔离绑定转为留档，不删除（流水/绑定历史必须保留） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :scrapped " +
           "WHERE se.equipmentId = :equipmentId AND se.dispatchStatus = :quarantined")
    int markScrappedByEquipmentId(@Param("equipmentId") Long equipmentId,
                                  @Param("scrapped") BindDispatchStatus scrapped,
                                  @Param("quarantined") BindDispatchStatus quarantined);
}