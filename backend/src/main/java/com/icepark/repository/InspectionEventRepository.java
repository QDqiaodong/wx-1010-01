package com.icepark.repository;

import com.icepark.entity.InspectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InspectionEventRepository extends JpaRepository<InspectionEvent, Long> {

    List<InspectionEvent> findByInspectionOrderIdOrderByEventTimeAscIdAsc(Long inspectionOrderId);

    List<InspectionEvent> findByEquipmentIdOrderByEventTimeDescIdDesc(Long equipmentId);
}
