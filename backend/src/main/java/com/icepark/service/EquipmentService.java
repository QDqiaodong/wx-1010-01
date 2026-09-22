package com.icepark.service;

import com.icepark.dto.EquipmentDTO;
import com.icepark.dto.AgeGroupSummaryDTO;
import com.icepark.entity.Equipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.EquipmentStatus;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.EquipmentDispatchRecordRepository;
import com.icepark.repository.EquipmentRepository;
import com.icepark.repository.InspectionOrderRepository;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentService {
    
    private final EquipmentRepository equipmentRepository;
    private final EquipmentDispatchRecordRepository dispatchRecordRepository;
    private final InspectionOrderRepository inspectionOrderRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String REDIS_KEY_FROST_PARAMS = "equipment:frost_params";
    private static final String REDIS_KEY_BY_AGEGROUP = "equipment:by_agegroup:";
    
    public List<EquipmentDTO> getAllEquipments() {
        return equipmentRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    public List<EquipmentDTO> getEquipmentsByAgeGroup(String ageGroup) {
        AgeGroup group = AgeGroup.valueOf(ageGroup.toUpperCase());
        return equipmentRepository.findByAgeGroup(group).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    public EquipmentDTO getEquipmentById(Long id) {
        Equipment equipment = equipmentRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + id));
        return convertToDTO(equipment);
    }
    
    @Transactional
    public EquipmentDTO createEquipment(EquipmentDTO dto) {
        if (equipmentRepository.existsByEquipmentCode(dto.getEquipmentCode())) {
            throw new RuntimeException("器材编号已存在: " + dto.getEquipmentCode());
        }
        
        Equipment equipment = new Equipment();
        equipment.setEquipmentCode(dto.getEquipmentCode());
        equipment.setName(dto.getName());
        equipment.setFrostResistanceSpec(dto.getFrostResistanceSpec());
        equipment.setAgeGroup(AgeGroup.valueOf(dto.getAgeGroup().toUpperCase()));
        equipment.setCategory(dto.getCategory());
        // 资产状态只能走"送检→复检/报废"状态机，新建一律可用，忽略表单传入的状态
        equipment.setStatus(EquipmentStatus.AVAILABLE);
        
        Equipment saved = equipmentRepository.save(equipment);
        updateRedisCache();
        return convertToDTO(saved);
    }
    
    @Transactional
    public EquipmentDTO updateEquipment(Long id, EquipmentDTO dto) {
        Equipment equipment = equipmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("器材不存在，ID: " + id));
        
        if (!equipment.getEquipmentCode().equals(dto.getEquipmentCode()) &&
                equipmentRepository.existsByEquipmentCode(dto.getEquipmentCode())) {
            throw new RuntimeException("器材编号已存在: " + dto.getEquipmentCode());
        }
        
        AgeGroup oldAgeGroup = equipment.getAgeGroup();
        
        equipment.setEquipmentCode(dto.getEquipmentCode());
        equipment.setName(dto.getName());
        equipment.setFrostResistanceSpec(dto.getFrostResistanceSpec());
        equipment.setAgeGroup(AgeGroup.valueOf(dto.getAgeGroup().toUpperCase()));
        equipment.setCategory(dto.getCategory());
        // 不允许通过编辑表单直接改资产状态：送检中/已报废等只能由送检流程驱动，避免绕过状态机
        // （equipment.status 保持数据库现值不变）
        
        Equipment saved = equipmentRepository.save(equipment);
        updateRedisCache();
        return convertToDTO(saved);
    }
    
    @Transactional
    public void deleteEquipment(Long id) {
        Equipment equipment = equipmentRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + id));
        // 有发装流水或送检历史的器材必须保留审计痕迹，只能报废不能物理删除
        if (dispatchRecordRepository.existsByEquipmentId(id)) {
            throw new ConflictException("器材「" + equipment.getEquipmentCode()
                    + "」存在发装/归还流水，不能删除（如不再使用请走送检报废流程）");
        }
        if (!inspectionOrderRepository.findByEquipmentIdOrderByReportTimeDescIdDesc(id).isEmpty()) {
            throw new ConflictException("器材「" + equipment.getEquipmentCode()
                    + "」存在送检记录，历史必须保留，不能删除");
        }
        equipmentRepository.delete(equipment);
        updateRedisCache();
    }
    
    public List<AgeGroupSummaryDTO> getSummaryByAgeGroup() {
        return java.util.Arrays.stream(AgeGroup.values())
                .map(group -> {
                    AgeGroupSummaryDTO summary = new AgeGroupSummaryDTO();
                    summary.setAgeGroup(group.name());
                    summary.setAgeGroupLabel(group.getLabel());
                    summary.setAgeRange(group.getAgeRange());
                    
                    List<EquipmentDTO> equipments = equipmentRepository.findByAgeGroup(group).stream()
                            .map(this::convertToDTO)
                            .collect(Collectors.toList());
                    
                    summary.setTotalCount(equipments.size());
                    summary.setEquipments(equipments);
                    return summary;
                })
                .collect(Collectors.toList());
    }
    
    private EquipmentDTO convertToDTO(Equipment equipment) {
        EquipmentDTO dto = new EquipmentDTO();
        dto.setId(equipment.getId());
        dto.setEquipmentCode(equipment.getEquipmentCode());
        dto.setName(equipment.getName());
        dto.setFrostResistanceSpec(equipment.getFrostResistanceSpec());
        dto.setAgeGroup(equipment.getAgeGroup().name());
        dto.setCategory(equipment.getCategory());
        dto.setStatus(equipment.getStatus().name());
        return dto;
    }
    
    private void updateRedisCache() {
        List<Equipment> allEquipments = equipmentRepository.findAll();
        String frostParamsJson = JSON.toJSONString(allEquipments.stream()
                .map(e -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", e.getId());
                    map.put("equipmentCode", e.getEquipmentCode());
                    map.put("name", e.getName());
                    map.put("frostResistanceSpec", e.getFrostResistanceSpec());
                    map.put("ageGroup", e.getAgeGroup().name());
                    return map;
                }).collect(Collectors.toList()));
        
        redisTemplate.delete(REDIS_KEY_FROST_PARAMS);
        redisTemplate.opsForList().rightPush(REDIS_KEY_FROST_PARAMS, frostParamsJson);
        
        for (AgeGroup group : AgeGroup.values()) {
            String key = REDIS_KEY_BY_AGEGROUP + group.name();
            redisTemplate.delete(key);
            List<Long> ids = equipmentRepository.findByAgeGroup(group).stream()
                    .map(Equipment::getId)
                    .collect(Collectors.toList());
            ids.forEach(id -> redisTemplate.opsForSet().add(key, id));
        }
        
        log.info("Redis缓存已更新");
    }
    
    public void refreshCache() {
        updateRedisCache();
    }
}