package com.icepark.repository;

import com.icepark.entity.Equipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.EquipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    Optional<Equipment> findByEquipmentCode(String equipmentCode);

    List<Equipment> findByAgeGroup(AgeGroup ageGroup);

    List<Equipment> findByCategory(String category);

    List<Equipment> findByStatus(EquipmentStatus status);

    List<Equipment> findByAgeGroupAndStatus(AgeGroup ageGroup, EquipmentStatus status);

    List<Equipment> findByAgeGroupIn(List<AgeGroup> ageGroups);

    boolean existsByEquipmentCode(String equipmentCode);

    /** 送检/报废/复检等资产状态变更先对器材行加悲观写锁，与发装/归还路径互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Equipment e WHERE e.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);
}