package com.icepark.repository;

import com.icepark.entity.Equipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.EquipmentStatus;
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
public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    Optional<Equipment> findByEquipmentCode(String equipmentCode);

    List<Equipment> findByAgeGroup(AgeGroup ageGroup);

    List<Equipment> findByCategory(String category);

    List<Equipment> findByStatus(EquipmentStatus status);

    List<Equipment> findByAgeGroupAndStatus(AgeGroup ageGroup, EquipmentStatus status);

    List<Equipment> findByAgeGroupIn(List<AgeGroup> ageGroups);

    boolean existsByEquipmentCode(String equipmentCode);

    /**
     * 对器材行加悲观写锁：送检登记/报废/复检等所有变更器材资产状态的入口都先锁器材行，
     * 与"先场次行锁再器材行锁"的统一加锁顺序配合，既串行化同器材并发，也杜绝交叉持锁死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Equipment e WHERE e.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 自动绑定的资产状态 CAS：只有仍是 AVAILABLE 的器材才能置为 IN_USE。
     * 自动绑定与现场送检并发时，被送检抢走的器材影响行数 0，跳过不绑定，绝不把送检中改回使用中。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Equipment e SET e.status = :inUse WHERE e.id = :id AND e.status = :available")
    int markInUseIfAvailable(@Param("id") Long id,
                             @Param("available") EquipmentStatus available,
                             @Param("inUse") EquipmentStatus inUse);

    /** 自动绑定 CAS 后发现器材已有未关闭送检单：仅把刚置成 IN_USE 的行安全回退为 AVAILABLE */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Equipment e SET e.status = :available WHERE e.id = :id AND e.status = :inUse")
    int markAvailableIfInUse(@Param("id") Long id,
                             @Param("inUse") EquipmentStatus inUse,
                             @Param("available") EquipmentStatus available);
}
