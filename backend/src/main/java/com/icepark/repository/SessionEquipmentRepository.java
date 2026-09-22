package com.icepark.repository;

import com.icepark.entity.SessionEquipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.BindDispatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByEquipmentId(Long equipmentId);

    /**
     * 发装的原子条件更新（CAS）：只有当前仍在架（AVAILABLE 或历史数据 NULL）的绑定才能置为 ISSUED。
     * 两个工作人员同时点同一件器材时，数据库行锁串行化两条 UPDATE，
     * 后者影响行数为 0，据此抛出"已被领用"。不依赖应用层先读后写，也不依赖前端禁用按钮。
     * 已送检冻结（PENDING）的绑定永远无法发装。
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

    /** 送检冻结：在架器材送检时 AVAILABLE -> PENDING，已领用/已冻结均返回 0 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :pending " +
           "WHERE se.equipmentId = :equipmentId AND " +
           "(se.dispatchStatus = :available OR se.dispatchStatus IS NULL)")
    int markPendingIfAvailable(@Param("equipmentId") Long equipmentId,
                               @Param("available") BindDispatchStatus available,
                               @Param("pending") BindDispatchStatus pending);

    /** 已领用器材转入待处理：ISSUED -> PENDING */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :pending " +
           "WHERE se.id = :id AND se.dispatchStatus = :issued")
    int markPendingIfIssued(@Param("id") Long id,
                            @Param("issued") BindDispatchStatus issued,
                            @Param("pending") BindDispatchStatus pending);

    /** 送检单关闭（复检通过）后：若绑定还在且处于冻结态，恢复在架可发装 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEquipment se SET se.dispatchStatus = :available " +
           "WHERE se.equipmentId = :equipmentId AND se.dispatchStatus = :pending")
    int markAvailableIfPending(@Param("equipmentId") Long equipmentId,
                               @Param("pending") BindDispatchStatus pending,
                               @Param("available") BindDispatchStatus available);
}
