package com.icepark.repository;

import com.icepark.entity.InspectionActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InspectionActionLogRepository extends JpaRepository<InspectionActionLog, Long> {

    /** 一张送检单的全部操作痕迹（时间正序，还原完整状态链路） */
    List<InspectionActionLog> findByOrderIdOrderByActionTimeAscIdAsc(Long orderId);

    List<InspectionActionLog> findByEquipmentIdOrderByActionTimeDescIdDesc(Long equipmentId);
}
