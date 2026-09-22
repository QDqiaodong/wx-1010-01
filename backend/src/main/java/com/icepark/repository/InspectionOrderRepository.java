package com.icepark.repository;

import com.icepark.entity.InspectionOrder;
import com.icepark.enums.InspectionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InspectionOrderRepository extends JpaRepository<InspectionOrder, Long> {

    /** 未关闭单据（提交送检时先查，给工作人员明确的中文冲突提示） */
    Optional<InspectionOrder> findByEquipmentIdAndOpenKeyIsNotNull(Long equipmentId);

    List<InspectionOrder> findByStatusOrderBySubmitTimeDescIdDesc(InspectionStatus status);

    List<InspectionOrder> findByEquipmentIdOrderBySubmitTimeDescIdDesc(Long equipmentId);

    List<InspectionOrder> findAllByOrderBySubmitTimeDescIdDesc();

    /** 处理动作都先对单据行加悲观写锁，串行化同一单据上的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM InspectionOrder o WHERE o.id = :id")
    Optional<InspectionOrder> findByIdForUpdate(@Param("id") Long id);

    boolean existsByEquipmentId(Long equipmentId);

    /** 列表页批量带出每件器材当前未关闭单据 */
    @Query("SELECT o FROM InspectionOrder o WHERE o.openKey IS NOT NULL AND o.equipmentId IN :equipmentIds")
    List<InspectionOrder> findOpenByEquipmentIds(@Param("equipmentIds") List<Long> equipmentIds);
}
