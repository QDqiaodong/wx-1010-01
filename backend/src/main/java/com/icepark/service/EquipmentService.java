package com.icepark.service;

import com.icepark.dto.EquipmentDTO;
import com.icepark.dto.AgeGroupSummaryDTO;
import com.icepark.entity.Equipment;
import com.icepark.entity.InspectionOrder;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.EquipmentStatus;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.EquipmentRepository;
import com.icepark.service.InspectionOrderService;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final InspectionOrderService inspectionOrderService;

    private static final String REDIS_KEY_FROST_PARAMS = "equipment:frost_params";
    private static final String REDIS_KEY_BY_AGEGROUP = "equipment:by_agegroup:";

    public List<EquipmentDTO> getAllEquipments() {
        List<Equipment> all = equipmentRepository.findAll();
        Map<Long, InspectionOrder> openMap = inspectionOrderService.mapOpenOrders(
                all.stream().map(Equipment::getId).toList());
        return all.stream().map(e -> convertToDTO(e, openMap.get(e.getId()))).collect(Collectors.toList());
    }

    public List<EquipmentDTO> getEquipmentsByAgeGroup(String ageGroup) {
        AgeGroup group = AgeGroup.valueOf(ageGroup.toUpperCase());
        List<Equipment> list = equipmentRepository.findByAgeGroup(group);
        Map<Long, InspectionOrder> openMap = inspectionOrderService.mapOpenOrders(
                list.stream().map(Equipment::getId).toList());
        return list.stream().map(e -> convertToDTO(e, openMap.get(e.getId()))).collect(Collectors.toList());
    }

    public EquipmentDTO getEquipmentById(Long id) {
        Equipment equipment = equipmentRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + id));
        return convertToDTO(equipment, inspectionOrderService.findOpenOrder(id).orElse(null));
    }
    
    @Transactional
    public EquipmentDTO createEquipment(EquipmentDTO dto) {
        if (equipmentRepository.existsByEquipmentCode(dto.getEquipmentCode())) {
            throw new BusinessValidationException("器材编号已存在: " + dto.getEquipmentCode());
        }
        EquipmentStatus requested = parseStatus(dto.getStatus());
        if (requested == EquipmentStatus.INSPECTION || requested == EquipmentStatus.SCRAPPED) {
            throw new BusinessValidationException("新增器材不能直接置为「" + requested.getLabel()
                    + "」，该状态由送检流程产生");
        }

        Equipment equipment = new Equipment();
        equipment.setEquipmentCode(dto.getEquipmentCode());
        equipment.setName(dto.getName());
        equipment.setFrostResistanceSpec(dto.getFrostResistanceSpec());
        equipment.setAgeGroup(AgeGroup.valueOf(dto.getAgeGroup().toUpperCase()));
        equipment.setCategory(dto.getCategory());
        equipment.setStatus(requested != null ? requested : EquipmentStatus.AVAILABLE);

        Equipment saved = equipmentRepository.save(equipment);
        updateRedisCache();
        return convertToDTO(saved, null);
    }

    @Transactional
    public EquipmentDTO updateEquipment(Long id, EquipmentDTO dto) {
        Equipment equipment = equipmentRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + id));

        if (!equipment.getEquipmentCode().equals(dto.getEquipmentCode()) &&
                equipmentRepository.existsByEquipmentCode(dto.getEquipmentCode())) {
            throw new BusinessValidationException("器材编号已存在: " + dto.getEquipmentCode());
        }

        AgeGroup oldAgeGroup = equipment.getAgeGroup();

        equipment.setEquipmentCode(dto.getEquipmentCode());
        equipment.setName(dto.getName());
        equipment.setFrostResistanceSpec(dto.getFrostResistanceSpec());
        equipment.setAgeGroup(AgeGroup.valueOf(dto.getAgeGroup().toUpperCase()));
        equipment.setCategory(dto.getCategory());

        if (dto.getStatus() != null) {
            EquipmentStatus requested = parseStatus(dto.getStatus());
            // 送检流程管理的状态不能被器材编辑表单覆盖（旧页面提交同样拦截）
            if (equipment.getStatus() == EquipmentStatus.INSPECTION
                    && requested != EquipmentStatus.INSPECTION) {
                throw new ConflictException("器材「" + equipment.getEquipmentCode()
                        + "」正在送检流程中，资产状态由送检台管理（当前：送检中），不能通过编辑修改");
            }
            if (equipment.getStatus() == EquipmentStatus.SCRAPPED) {
                throw new ConflictException("器材「" + equipment.getEquipmentCode()
                        + "」已报废，资产状态不可修改（历史保留）");
            }
            if (requested == EquipmentStatus.INSPECTION || requested == EquipmentStatus.SCRAPPED) {
                throw new BusinessValidationException("「" + requested.getLabel()
                        + "」只能由送检流程产生，不能手工设置");
            }
            equipment.setStatus(requested);
        }

        Equipment saved = equipmentRepository.save(equipment);
        updateRedisCache();
        return convertToDTO(saved, inspectionOrderService.findOpenOrder(id).orElse(null));
    }

    @Transactional
    public void deleteEquipment(Long id) {
        Equipment equipment = equipmentRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + id));
        // 有送检单（含已关闭、已报废）的器材必须保留历史，禁止物理删除
        if (inspectionOrderService.hasAnyOrder(id)) {
            throw new ConflictException("器材「" + equipment.getEquipmentCode()
                    + "」存在送检记录，历史必须保留，不能删除；报废器材已自动退出可用池与可绑定清单");
        }
        equipmentRepository.deleteById(id);
        updateRedisCache();
    }

    public List<AgeGroupSummaryDTO> getSummaryByAgeGroup() {
        return java.util.Arrays.stream(AgeGroup.values())
                .map(group -> {
                    AgeGroupSummaryDTO summary = new AgeGroupSummaryDTO();
                    summary.setAgeGroup(group.name());
                    summary.setAgeGroupLabel(group.getLabel());
                    summary.setAgeRange(group.getAgeRange());

                    List<Equipment> list = equipmentRepository.findByAgeGroup(group);
                    Map<Long, InspectionOrder> openMap = inspectionOrderService.mapOpenOrders(
                            list.stream().map(Equipment::getId).toList());
                    List<EquipmentDTO> equipments = list.stream()
                            .map(e -> convertToDTO(e, openMap.get(e.getId())))
                            .collect(Collectors.toList());

                    summary.setTotalCount(equipments.size());
                    summary.setEquipments(equipments);
                    return summary;
                })
                .collect(Collectors.toList());
    }

    private EquipmentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EquipmentStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessValidationException("器材状态不合法：" + raw);
        }
    }

    private EquipmentDTO convertToDTO(Equipment equipment, InspectionOrder openOrder) {
        EquipmentDTO dto = new EquipmentDTO();
        dto.setId(equipment.getId());
        dto.setEquipmentCode(equipment.getEquipmentCode());
        dto.setName(equipment.getName());
        dto.setFrostResistanceSpec(equipment.getFrostResistanceSpec());
        dto.setAgeGroup(equipment.getAgeGroup().name());
        dto.setCategory(equipment.getCategory());
        dto.setStatus(equipment.getStatus().name());
        dto.setStatusLabel(equipment.getStatus().getLabel());
        dto.setOpenInspectionId(openOrder != null ? openOrder.getId() : null);
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