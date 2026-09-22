package com.icepark.repository;

import com.icepark.entity.InspectionOrder;
import com.icepark.enums.InspectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InspectionOrderRepository extends JpaRepository<InspectionOrder, Long> {

    /** 某件器材的全部送检单（历史不覆盖，按时间倒序） */
    List<InspectionOrder> findByEquipmentIdOrderByReportTimeDescIdDesc(Long equipmentId);

    Optional<InspectionOrder> findFirstByEquipmentIdAndStatusInOrderByIdDesc(
            Long equipmentId, List<InspectionStatus> statuses);

    List<InspectionOrder> findByStatusInOrderByReportTimeDescIdDesc(List<InspectionStatus> statuses);

    List<InspectionOrder> findAllByOrderByReportTimeDescIdDesc();

    boolean existsByEquipmentIdAndStatusIn(Long equipmentId, List<InspectionStatus> statuses);

    List<InspectionOrder> findByEquipmentIdAndStatusIn(Long equipmentId, List<InspectionStatus> statuses);

    List<InspectionOrder> findByEquipmentIdInAndStatusIn(List<Long> equipmentIds, List<InspectionStatus> statuses);

    boolean existsByOpenKey(String openKey);

    /** 终态转换 CAS：仅当单据仍处于指定状态之一时才关闭，返回 0 说明已被别人处理（旧页面提交） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionOrder o SET o.status = :toStatus, o.handler = :handler, " +
           "o.conclusion = :conclusion, o.closeTime = :closeTime, o.openKey = NULL " +
           "WHERE o.id = :id AND o.status IN :fromStatuses")
    int closeIfInStatuses(@Param("id") Long id,
                          @Param("fromStatuses") List<InspectionStatus> fromStatuses,
                          @Param("toStatus") InspectionStatus toStatus,
                          @Param("handler") String handler,
                          @Param("conclusion") String conclusion,
                          @Param("closeTime") java.time.LocalDateTime closeTime);

    /** 中间态转换 CAS：退回补充材料 / 补充后重提 / 提交复检 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionOrder o SET o.status = :toStatus, o.handler = :handler " +
           "WHERE o.id = :id AND o.status = :fromStatus")
    int transitionIfStatus(@Param("id") Long id,
                           @Param("fromStatus") InspectionStatus fromStatus,
                           @Param("toStatus") InspectionStatus toStatus,
                           @Param("handler") String handler);

    /** 待归还 -> 维修队列（游客正常归还/兜底收回/转入待处理后），同样做条件更新 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionOrder o SET o.status = :toStatus " +
           "WHERE o.id = :id AND o.status = :fromStatus")
    int advanceFromPendingReturn(@Param("id") Long id,
                                 @Param("fromStatus") InspectionStatus fromStatus,
                                 @Param("toStatus") InspectionStatus toStatus);
}
