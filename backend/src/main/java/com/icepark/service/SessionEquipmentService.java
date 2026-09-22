package com.icepark.service;

import com.icepark.dto.AdjustRequestDTO;
import com.icepark.dto.SessionEquipmentDTO;
import com.icepark.entity.AdjustRecord;
import com.icepark.entity.Equipment;
import com.icepark.entity.Session;
import com.icepark.entity.SessionEquipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.BindDispatchStatus;
import com.icepark.enums.EquipmentStatus;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.AdjustRecordRepository;
import com.icepark.repository.EquipmentRepository;
import com.icepark.repository.SessionEquipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionEquipmentService {
    
    private final SessionEquipmentRepository sessionEquipmentRepository;
    private final EquipmentRepository equipmentRepository;
    private final AdjustRecordRepository adjustRecordRepository;
    private final SessionService sessionService;
    private final EquipmentDispatchService equipmentDispatchService;
    private final InspectionOrderService inspectionOrderService;

    @Transactional
    public SessionEquipmentDTO bindEquipment(Long sessionId, Long equipmentId, String targetAgeGroup) {
        // 锁序与发装/送检一致：先场次行，再器材行，避免与"现场提交送检"交叉死锁
        sessionService.lockSession(sessionId);

        if (sessionEquipmentRepository.existsBySessionIdAndEquipmentId(sessionId, equipmentId)) {
            throw new BusinessValidationException("该器材已绑定到场次");
        }

        // 已在其他进行中场次发给游客且未归还的器材，不允许绑定到新场次
        equipmentDispatchService.assertEquipmentNotOutstanding(equipmentId);

        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + equipmentId));

        // 送检中 / 已报废的器材不能进入任何场次的可绑定清单。
        // 旧页面停留期间器材被别人送检/报废时，提交绑定在这里被挡下并给出明确提示，不覆盖新状态。
        inspectionOrderService.findOpenOrder(equipmentId).ifPresent(o -> {
            throw new ConflictException("器材「" + equipment.getEquipmentCode() + " " + equipment.getName()
                    + "」已被送检（送检单 #" + o.getId() + "，状态：" + o.getStatus().getLabel()
                    + "），不能绑定，请刷新页面后选择其他器材");
        });
        if (equipment.getStatus() == EquipmentStatus.SCRAPPED) {
            throw new ConflictException("器材「" + equipment.getEquipmentCode() + " " + equipment.getName()
                    + "」已报废，已从可绑定清单移除，请刷新页面");
        }
        if (equipment.getStatus() == EquipmentStatus.INSPECTION) {
            // 理论上被未关闭单据守卫覆盖，作为资产状态一致性兜底
            throw new ConflictException("器材「" + equipment.getEquipmentCode() + "」送检中，不能绑定，请刷新页面");
        }

        SessionEquipment sessionEquipment = new SessionEquipment();
        sessionEquipment.setSessionId(sessionId);
        sessionEquipment.setEquipmentId(equipmentId);
        sessionEquipment.setTargetAgeGroup(AgeGroup.valueOf(targetAgeGroup.toUpperCase()));
        sessionEquipment.setDispatchStatus(BindDispatchStatus.AVAILABLE);

        SessionEquipment saved = sessionEquipmentRepository.save(sessionEquipment);

        equipment.setStatus(EquipmentStatus.IN_USE);
        equipmentRepository.save(equipment);

        return convertToDTO(saved);
    }
    
    public List<SessionEquipmentDTO> getSessionEquipments(Long sessionId) {
        return sessionEquipmentRepository.findBySessionId(sessionId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    @Transactional
    public void unbindEquipment(Long sessionId, Long equipmentId) {
        // 已发出未归还的器材不允许解绑，避免流水悬空、器材无法归还
        equipmentDispatchService.assertNotOutstandingInSession(sessionId, equipmentId);

        SessionEquipment sessionEquipment = sessionEquipmentRepository.findBySessionId(sessionId).stream()
                .filter(se -> se.getEquipmentId().equals(equipmentId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("该器材未绑定到场次"));

        // 送检隔离/报废留档的绑定行不允许解绑：它承载送检期间的归属信息，
        // 复检通过由系统复位在架、报废则永久留档，人工解绑会破坏一致性
        if (sessionEquipment.getDispatchStatus() == BindDispatchStatus.QUARANTINED) {
            throw new ConflictException("器材已送检隔离，不能解绑；复检通过后会自动复位，报废则该绑定留档");
        }
        if (sessionEquipment.getDispatchStatus() == BindDispatchStatus.SCRAPPED) {
            throw new ConflictException("器材已报废，绑定记录必须保留备查，不能解绑");
        }

        sessionEquipmentRepository.delete(sessionEquipment);

        Equipment equipment = equipmentRepository.findById(equipmentId).orElse(null);
        if (equipment != null) {
            boolean isUsedElsewhere = sessionEquipmentRepository.findByEquipmentId(equipmentId).stream()
                    .anyMatch(se -> !se.getSessionId().equals(sessionId));

            // 只有"使用中"且无其他绑定才复位可用；送检中/已报废由送检流程管理，解绑不能改写
            if (!isUsedElsewhere && equipment.getStatus() == EquipmentStatus.IN_USE) {
                equipment.setStatus(EquipmentStatus.AVAILABLE);
                equipmentRepository.save(equipment);
            }
        }
    }
    
    @Transactional
    public void adjustEquipmentAgeGroup(Long sessionId, Long equipmentId,
                                         String newAgeGroup, String adjustReason, String operator) {
        SessionEquipment sessionEquipment = sessionEquipmentRepository.findBySessionId(sessionId).stream()
                .filter(se -> se.getEquipmentId().equals(equipmentId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("该器材未绑定到场次"));

        if (sessionEquipment.getDispatchStatus() == BindDispatchStatus.QUARANTINED
                || sessionEquipment.getDispatchStatus() == BindDispatchStatus.SCRAPPED) {
            throw new ConflictException("器材处于「" + sessionEquipment.getDispatchStatus().getLabel()
                    + "」，由送检流程管理，不能调整绑定年龄段");
        }

        AgeGroup oldAgeGroup = sessionEquipment.getTargetAgeGroup();
        AgeGroup newGroup = AgeGroup.valueOf(newAgeGroup.toUpperCase());

        if (oldAgeGroup == newGroup) {
            throw new BusinessValidationException("新年龄段与原年龄段相同");
        }
        
        sessionEquipment.setTargetAgeGroup(newGroup);
        sessionEquipmentRepository.save(sessionEquipment);
        
        AdjustRecord record = new AdjustRecord();
        record.setSessionId(sessionId);
        record.setEquipmentId(equipmentId);
        record.setOldAgeGroup(oldAgeGroup);
        record.setNewAgeGroup(newGroup);
        record.setAdjustReason(adjustReason);
        record.setAdjustOperator(operator);
        adjustRecordRepository.save(record);
        
        log.info("器材年龄段调整: 场次{} 器材{} 从{}调整为{}", sessionId, equipmentId, oldAgeGroup, newGroup);
    }
    
    @Transactional
    public List<AdjustRecord> autoAdjustByRatio(AdjustRequestDTO request) {
        Long sessionId = request.getSessionId();
        Session session = sessionService.getSessionEntity(sessionId);
        
        BigDecimal childRatio = request.getChildRatio() != null ? request.getChildRatio() : session.getChildRatio();
        BigDecimal teenRatio = request.getTeenRatio() != null ? request.getTeenRatio() : session.getTeenRatio();
        BigDecimal adultRatio = request.getAdultRatio() != null ? request.getAdultRatio() : session.getAdultRatio();
        
        session.setChildRatio(childRatio);
        session.setTeenRatio(teenRatio);
        session.setAdultRatio(adultRatio);
        
        List<SessionEquipment> currentBindings = sessionEquipmentRepository.findBySessionId(sessionId).stream()
                // 送检隔离/报废留档的绑定由送检流程管理，不参与年龄段重分配
                .filter(se -> se.getDispatchStatus() != BindDispatchStatus.QUARANTINED
                        && se.getDispatchStatus() != BindDispatchStatus.SCRAPPED)
                .toList();
        
        Map<AgeGroup, List<SessionEquipment>> groupedByAge = currentBindings.stream()
                .collect(Collectors.groupingBy(SessionEquipment::getTargetAgeGroup));
        
        int totalEquipment = currentBindings.size();
        int childCount = (int) Math.round(childRatio.multiply(BigDecimal.valueOf(totalEquipment)).doubleValue());
        int teenCount = (int) Math.round(teenRatio.multiply(BigDecimal.valueOf(totalEquipment)).doubleValue());
        int adultCount = totalEquipment - childCount - teenCount;
        
        List<AdjustRecord> records = new ArrayList<>();
        
        redistributeEquipments(AgeGroup.CHILD, groupedByAge.getOrDefault(AgeGroup.CHILD, new ArrayList<>()), 
                               childCount, sessionId, request.getAdjustReason(), request.getAdjustOperator(), records);
        redistributeEquipments(AgeGroup.TEEN, groupedByAge.getOrDefault(AgeGroup.TEEN, new ArrayList<>()), 
                               teenCount, sessionId, request.getAdjustReason(), request.getAdjustOperator(), records);
        redistributeEquipments(AgeGroup.ADULT, groupedByAge.getOrDefault(AgeGroup.ADULT, new ArrayList<>()), 
                               adultCount, sessionId, request.getAdjustReason(), request.getAdjustOperator(), records);
        
        return records;
    }
    
    private void redistributeEquipments(AgeGroup targetGroup, List<SessionEquipment> currentEquipments,
                                        int targetCount, Long sessionId, String reason, String operator,
                                        List<AdjustRecord> records) {
        if (currentEquipments.size() == targetCount) {
            return;
        }
        
        if (currentEquipments.size() > targetCount) {
            int excess = currentEquipments.size() - targetCount;
            List<SessionEquipment> toMove = new ArrayList<>(currentEquipments.subList(0, excess));
            
            for (SessionEquipment se : toMove) {
                AgeGroup oldGroup = se.getTargetAgeGroup();
                se.setTargetAgeGroup(targetGroup);
                sessionEquipmentRepository.save(se);
                
                AdjustRecord record = new AdjustRecord();
                record.setSessionId(sessionId);
                record.setEquipmentId(se.getEquipmentId());
                record.setOldAgeGroup(oldGroup);
                record.setNewAgeGroup(targetGroup);
                record.setAdjustReason(reason);
                record.setAdjustOperator(operator);
                records.add(adjustRecordRepository.save(record));
            }
        }
    }
    
    @Transactional
    public void autoBindByRatio(Long sessionId) {
        // 与手动绑定/发装/送检同一把场次锁，避免自动绑定与送检并发交叉
        Session session = sessionService.lockSession(sessionId);

        List<Equipment> availableEquipments = equipmentRepository.findByStatus(EquipmentStatus.AVAILABLE);
        
        Map<AgeGroup, List<Equipment>> availableByAge = availableEquipments.stream()
                .collect(Collectors.groupingBy(Equipment::getAgeGroup));
        
        int totalAvailable = availableEquipments.size();
        int childCount = (int) Math.round(session.getChildRatio().multiply(BigDecimal.valueOf(totalAvailable)).doubleValue());
        int teenCount = (int) Math.round(session.getTeenRatio().multiply(BigDecimal.valueOf(totalAvailable)).doubleValue());
        int adultCount = totalAvailable - childCount - teenCount;
        
        bindEquipments(sessionId, availableByAge.getOrDefault(AgeGroup.CHILD, new ArrayList<>()), 
                       AgeGroup.CHILD, childCount);
        bindEquipments(sessionId, availableByAge.getOrDefault(AgeGroup.TEEN, new ArrayList<>()), 
                       AgeGroup.TEEN, teenCount);
        bindEquipments(sessionId, availableByAge.getOrDefault(AgeGroup.ADULT, new ArrayList<>()), 
                       AgeGroup.ADULT, adultCount);
        
        log.info("场次{}自动绑定完成", sessionId);
    }
    
    private void bindEquipments(Long sessionId, List<Equipment> equipments, AgeGroup targetGroup, int count) {
        int actualCount = Math.min(count, equipments.size());
        for (int i = 0; i < actualCount; i++) {
            Equipment equipment = equipments.get(i);

            // 自动绑定同样必须服从送检守卫：列表来自 AVAILABLE 查询，这里再做一次未关闭单据复检，
            // 防止"页面停留期间器材被送检"后自动绑定把它重新带回场次
            if (inspectionOrderService.findOpenOrder(equipment.getId()).isPresent()
                    || equipment.getStatus() != EquipmentStatus.AVAILABLE) {
                continue;
            }
            if (sessionEquipmentRepository.existsBySessionIdAndEquipmentId(sessionId, equipment.getId())) {
                continue;
            }

            SessionEquipment sessionEquipment = new SessionEquipment();
            sessionEquipment.setSessionId(sessionId);
            sessionEquipment.setEquipmentId(equipment.getId());
            sessionEquipment.setTargetAgeGroup(targetGroup);
            sessionEquipmentRepository.save(sessionEquipment);

            equipment.setStatus(EquipmentStatus.IN_USE);
            equipmentRepository.save(equipment);
        }
    }
    
    private SessionEquipmentDTO convertToDTO(SessionEquipment sessionEquipment) {
        SessionEquipmentDTO dto = new SessionEquipmentDTO();
        dto.setId(sessionEquipment.getId());
        dto.setSessionId(sessionEquipment.getSessionId());
        dto.setEquipmentId(sessionEquipment.getEquipmentId());
        dto.setTargetAgeGroup(sessionEquipment.getTargetAgeGroup().name());
        return dto;
    }
}