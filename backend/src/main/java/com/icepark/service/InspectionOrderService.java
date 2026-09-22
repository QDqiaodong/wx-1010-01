package com.icepark.service;

import com.icepark.dto.InspectionActionRequestDTO;
import com.icepark.dto.InspectionCreateRequestDTO;
import com.icepark.dto.InspectionEventDTO;
import com.icepark.dto.InspectionOrderDTO;
import com.icepark.dto.InspectionOrderDetailDTO;
import com.icepark.entity.Equipment;
import com.icepark.entity.EquipmentDispatchRecord;
import com.icepark.entity.InspectionEvent;
import com.icepark.entity.InspectionOrder;
import com.icepark.entity.SessionEquipment;
import com.icepark.enums.BindDispatchStatus;
import com.icepark.enums.DispatchStatus;
import com.icepark.enums.EquipmentStatus;
import com.icepark.enums.InspectionAction;
import com.icepark.enums.InspectionStatus;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.EquipmentDispatchRecordRepository;
import com.icepark.repository.EquipmentRepository;
import com.icepark.repository.InspectionEventRepository;
import com.icepark.repository.InspectionOrderRepository;
import com.icepark.repository.SessionEquipmentRepository;
import com.icepark.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 器材送检台：把"送检 → 退回补材料 → 维修 → 复检（可能反复不通过）→ 重新可用 / 报废"串成状态机。
 *
 * 并发与一致性原则（全部在后端/数据库保证，不靠前端禁用按钮）：
 * 1) 同一件器材至多一张未关闭送检单：open_key 唯一索引兜底，应用层先查给中文提示；
 * 2) 送检/报废先按固定顺序锁"场次行 → 器材行"，与发装/归还/结束场次的加锁路径完全一致，避免死锁；
 * 3) 已发给游客的器材送检必须先归还或"转入待处理"（关闭当前流水、隔离绑定行），不留悬空记录；
 * 4) 每次状态变化追加 inspection_event，历史只增不改，复检通过/报废只改单据当前状态，不覆盖旧痕迹。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InspectionOrderService {

    /** 未关闭送检单的互斥键前缀，配合 inspection_order 表唯一索引 */
    private static final String OPEN_KEY_PREFIX = "inspection:";
    /** 转入待处理流水的操作人前缀，保留现场发现人，便于事后核查 */
    private static final String TRANSFER_OPERATOR_PREFIX = "送检转入待处理（发现人：";

    private final InspectionOrderRepository inspectionOrderRepository;
    private final InspectionEventRepository inspectionEventRepository;
    private final EquipmentRepository equipmentRepository;
    private final SessionEquipmentRepository sessionEquipmentRepository;
    private final EquipmentDispatchRecordRepository dispatchRecordRepository;
    private final SessionRepository sessionRepository;

    // ==================== 查询 ====================

    /** 送检单列表：open=只看未关闭，closed=只看已关闭，null=全部 */
    @Transactional(readOnly = true)
    public List<InspectionOrderDTO> listOrders(Boolean open) {
        List<InspectionOrder> orders;
        if (Boolean.TRUE.equals(open)) {
            orders = new ArrayList<>();
            for (InspectionStatus s : List.of(InspectionStatus.SUBMITTED,
                    InspectionStatus.MATERIAL_NEEDED, InspectionStatus.REINSPECTING)) {
                orders.addAll(inspectionOrderRepository.findByStatusOrderBySubmitTimeDescIdDesc(s));
            }
            orders.sort((a, b) -> b.getSubmitTime().compareTo(a.getSubmitTime()));
        } else if (Boolean.FALSE.equals(open)) {
            orders = new ArrayList<>();
            for (InspectionStatus s : List.of(InspectionStatus.CLOSED_PASSED, InspectionStatus.CLOSED_SCRAPPED)) {
                orders.addAll(inspectionOrderRepository.findByStatusOrderBySubmitTimeDescIdDesc(s));
            }
            orders.sort((a, b) -> b.getSubmitTime().compareTo(a.getSubmitTime()));
        } else {
            orders = inspectionOrderRepository.findAllByOrderBySubmitTimeDescIdDesc();
        }
        Map<Long, Equipment> equipmentMap = loadEquipmentMap(orders.stream()
                .map(InspectionOrder::getEquipmentId).distinct().toList());
        return orders.stream().map(o -> toOrderDTO(o, equipmentMap.get(o.getEquipmentId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<InspectionOrderDTO> listByEquipment(Long equipmentId) {
        if (!equipmentRepository.existsById(equipmentId)) {
            throw new BusinessValidationException("器材不存在，ID: " + equipmentId);
        }
        List<InspectionOrder> orders =
                inspectionOrderRepository.findByEquipmentIdOrderBySubmitTimeDescIdDesc(equipmentId);
        Equipment equipment = equipmentRepository.findById(equipmentId).orElse(null);
        return orders.stream().map(o -> toOrderDTO(o, equipment)).toList();
    }

    @Transactional(readOnly = true)
    public InspectionOrderDetailDTO getDetail(Long id) {
        InspectionOrder order = inspectionOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("送检单不存在，ID: " + id));
        List<InspectionEvent> events =
                inspectionEventRepository.findByInspectionOrderIdOrderByEventTimeAscIdAsc(id);
        Equipment equipment = equipmentRepository.findById(order.getEquipmentId()).orElse(null);

        InspectionOrderDetailDTO detail = new InspectionOrderDetailDTO();
        detail.setOrder(toOrderDTO(order, equipment));
        if (equipment != null) {
            detail.setEquipmentStatus(equipment.getStatus().name());
            detail.setEquipmentStatusLabel(equipment.getStatus().getLabel());
        }
        detail.setEvents(events.stream().map(this::toEventDTO).toList());
        return detail;
    }

    /** 器材列表批量带出"当前未关闭送检单" */
    @Transactional(readOnly = true)
    public Map<Long, InspectionOrder> mapOpenOrders(List<Long> equipmentIds) {
        if (equipmentIds == null || equipmentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return inspectionOrderRepository.findOpenByEquipmentIds(equipmentIds).stream()
                .collect(Collectors.toMap(InspectionOrder::getEquipmentId, Function.identity(), (a, b) -> a));
    }

    // ==================== 提交送检 ====================

    /**
     * 现场工作人员提交送检。
     * 加锁顺序：场次行（一件器材绑定多个场次时按ID升序）→ 该器材的绑定行 → 器材行，
     * 与发装/归还/结束场次的"场次行优先"锁序完全一致：
     * 发装先拿到场次锁后，送检等待场次锁；送检先拿到全部场次锁后，发装等待，无循环等待。
     */
    @Transactional
    public InspectionOrderDetailDTO submit(InspectionCreateRequestDTO request) {
        Long equipmentId = request.getEquipmentId();

        // 普通读取（不加锁）找出关联场次，随后按ID升序对场次加悲观写锁
        List<Long> sessionIds = sessionEquipmentRepository.findByEquipmentId(equipmentId).stream()
                .map(SessionEquipment::getSessionId)
                .distinct()
                .sorted()
                .toList();
        if (!sessionIds.isEmpty()) {
            sessionRepository.findByIdsForUpdate(sessionIds);
        }
        // 锁绑定行：与同场次发装的 CAS 更新互斥（场次锁已在手上，不会与发装交叉）
        sessionEquipmentRepository.findByEquipmentIdForUpdate(equipmentId);
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + equipmentId));

        if (equipment.getStatus() == EquipmentStatus.SCRAPPED) {
            throw new ConflictException("器材「" + equipment.getEquipmentCode() + "」已报废，不能再送检");
        }
        if (equipment.getStatus() == EquipmentStatus.INSPECTION) {
            InspectionOrder existing =
                    inspectionOrderRepository.findByEquipmentIdAndOpenKeyIsNotNull(equipmentId).orElse(null);
            String where = existing != null ? "，送检单号 #" + existing.getId() : "";
            throw new ConflictException("器材「" + equipment.getEquipmentCode()
                    + "」已存在未关闭的送检单" + where + "，请先完成维修复检或报废，不能重复送检");
        }

        boolean forceTransfer = Boolean.TRUE.equals(request.getForceTransfer());
        Optional<EquipmentDispatchRecord> outstanding = dispatchRecordRepository
                .findFirstByEquipmentIdAndStatus(equipmentId, DispatchStatus.ISSUED);
        if (outstanding.isPresent() && !forceTransfer) {
            EquipmentDispatchRecord r = outstanding.get();
            throw new ConflictException("器材「" + equipment.getEquipmentCode() + "」已在场次 #" + r.getSessionId()
                    + " 发给游客「" + r.getVisitorName() + "」且未归还：请先按当前流水归还，"
                    + "或勾选「转入待处理」先行收回再送检（流水将保留为「转入待处理」）");
        }

        LocalDateTime now = LocalDateTime.now();

        // 游客使用中 → 转入待处理：先关闭当前流水（不允许悬空），再隔离该场次绑定行
        boolean transferred = false;
        if (outstanding.isPresent()) {
            EquipmentDispatchRecord r = outstanding.get();
            int closed = dispatchRecordRepository.closeRecord(
                    r.getId(), DispatchStatus.TRANSFERRED_PENDING,
                    TRANSFER_OPERATOR_PREFIX + request.getReporter().trim() + "）", now);
            if (closed == 0) {
                // 极端竞态：持锁后仍被并发归还/兜底（理论上已被场次+器材锁串行化，这里作数据一致性兜底）
                throw new ConflictException("该器材的发装流水刚被归还或收回，请刷新后重新提交送检");
            }
            transferred = true;
            log.warn("送检转入待处理: 器材{} 场次{} 原领用人{} 发现人{}",
                    equipmentId, r.getSessionId(), r.getVisitorName(), request.getReporter());
        }

        // 资产置为送检中，全部既有绑定（在架/已领用）隔离，新的场次绑定和发装从此被挡住
        equipment.setStatus(EquipmentStatus.INSPECTION);
        equipmentRepository.save(equipment);
        int quarantined = sessionEquipmentRepository.markQuarantinedByEquipmentId(
                equipmentId, BindDispatchStatus.QUARANTINED,
                BindDispatchStatus.AVAILABLE, BindDispatchStatus.ISSUED);

        InspectionOrder order = new InspectionOrder();
        order.setEquipmentId(equipmentId);
        order.setReporter(request.getReporter().trim());
        order.setProblemDescription(request.getProblemDescription().trim());
        order.setSubmitTime(now);
        order.setStatus(InspectionStatus.SUBMITTED);
        order.setHandler(null);
        order.setConclusion(null);
        order.setUpdateTime(now);
        order.setOpenKey(openKey(equipmentId));
        try {
            order = inspectionOrderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // 唯一索引兜底：并发两张送检单，后者失败
            log.warn("送检命中未关闭单据唯一约束，equipmentId={}", equipmentId, e);
            throw new ConflictException("器材「" + equipment.getEquipmentCode()
                    + "」已有未关闭的送检单，不能重复送检");
        }

        String note = request.getProblemDescription().trim()
                + (transferred ? "（提交时器材在游客使用中，已先转入待处理）" : "");
        appendEvent(order, InspectionAction.SUBMIT, null, InspectionStatus.SUBMITTED,
                request.getReporter().trim(), note, now);

        log.info("送检单创建: #{} 器材{} 发现人{} 隔离绑定{}行 转入待处理={}",
                order.getId(), equipmentId, order.getReporter(), quarantined, transferred);
        return getDetail(order.getId());
    }

    // ==================== 维修侧处理 ====================

    /** 维修退回补充材料：SUBMITTED / REINSPECTING（复检中发现材料不足）→ MATERIAL_NEEDED */
    @Transactional
    public InspectionOrderDetailDTO returnMaterials(Long id, InspectionActionRequestDTO request) {
        InspectionOrder order = lockOpenOrder(id);
        if (order.getStatus() != InspectionStatus.SUBMITTED
                && order.getStatus() != InspectionStatus.REINSPECTING) {
            throw new BusinessValidationException("当前状态为「" + order.getStatus().getLabel()
                    + "」，只有待维修/复检中的单据可以退回补充材料");
        }
        return transition(order, InspectionAction.RETURN_MATERIALS, InspectionStatus.MATERIAL_NEEDED,
                request.getHandler(), request.getNote(), true);
    }

    /** 材料补齐重新提交：MATERIAL_NEEDED → SUBMITTED */
    @Transactional
    public InspectionOrderDetailDTO resubmit(Long id, InspectionActionRequestDTO request) {
        InspectionOrder order = lockOpenOrder(id);
        if (order.getStatus() != InspectionStatus.MATERIAL_NEEDED) {
            throw new BusinessValidationException("当前状态为「" + order.getStatus().getLabel()
                    + "」，只有待补充材料的单据可以补齐重新提交");
        }
        return transition(order, InspectionAction.RESUBMIT, InspectionStatus.SUBMITTED,
                request.getHandler(), request.getNote(), true);
    }

    /** 维修完成提交复检：SUBMITTED → REINSPECTING */
    @Transactional
    public InspectionOrderDetailDTO submitReinspection(Long id, InspectionActionRequestDTO request) {
        InspectionOrder order = lockOpenOrder(id);
        if (order.getStatus() != InspectionStatus.SUBMITTED) {
            throw new BusinessValidationException("当前状态为「" + order.getStatus().getLabel()
                    + "」，只有待维修的单据可以提交复检（复检不通过退回后也需重新提交）");
        }
        return transition(order, InspectionAction.SUBMIT_REINSPECTION, InspectionStatus.REINSPECTING,
                request.getHandler(), request.getNote(), true);
    }

    /**
     * 复检通过：REINSPECTING → CLOSED_PASSED。
     * 器材重新回到可用池：无任何场场绑定为 AVAILABLE，仍有绑定为 IN_USE；
     * 送检隔离的绑定行复位在架（进行中场次可立刻重新发装，已结束场次也保持状态一致）。
     */
    @Transactional
    public InspectionOrderDetailDTO passReinspection(Long id, InspectionActionRequestDTO request) {
        InspectionOrder order = lockOpenOrder(id);
        if (order.getStatus() != InspectionStatus.REINSPECTING) {
            throw new BusinessValidationException("当前状态为「" + order.getStatus().getLabel()
                    + "」，只有复检中的单据可以登记复检结果");
        }

        Equipment equipment = equipmentRepository.findByIdForUpdate(order.getEquipmentId())
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + order.getEquipmentId()));
        if (equipment.getStatus() != EquipmentStatus.INSPECTION
                && equipment.getStatus() != EquipmentStatus.AVAILABLE
                && equipment.getStatus() != EquipmentStatus.IN_USE) {
            // 例如已报废：数据一致性兜底，不能放行
            throw new ConflictException("器材当前资产状态为「" + equipment.getStatus().getLabel()
                    + "」，不能复检放行，请刷新后联系管理员核查");
        }

        int restored = sessionEquipmentRepository.markAvailableByEquipmentIdIfQuarantined(
                equipment.getId(), BindDispatchStatus.AVAILABLE, BindDispatchStatus.QUARANTINED);
        boolean stillBound = !sessionEquipmentRepository.findByEquipmentId(equipment.getId()).isEmpty();
        // 归还路径可能已把资产从 INSPECTION 带成 AVAILABLE/IN_USE，这里只在仍是送检中时统一收敛，避免误改
        if (equipment.getStatus() == EquipmentStatus.INSPECTION) {
            equipment.setStatus(stillBound ? EquipmentStatus.IN_USE : EquipmentStatus.AVAILABLE);
            equipmentRepository.save(equipment);
        }

        LocalDateTime now = LocalDateTime.now();
        closeOrder(order, InspectionAction.REINSPECTION_PASS, InspectionStatus.CLOSED_PASSED,
                request.getHandler(),
                defaultNote(request.getNote(), "复检通过，器材重新放行" + (stillBound ? "（仍绑定场次）" : "（回到可用池）")),
                now);

        log.info("复检通过: 送检单#{} 器材{} 复位绑定{}行 资产状态={} 处理人{}",
                order.getId(), equipment.getId(), restored, equipment.getStatus(), request.getHandler());
        return getDetail(order.getId());
    }

    /**
     * 复检不通过：REINSPECTING → SUBMITTED（退回继续维修，可再次提交复检，历史不覆盖）。
     */
    @Transactional
    public InspectionOrderDetailDTO failReinspection(Long id, InspectionActionRequestDTO request) {
        InspectionOrder order = lockOpenOrder(id);
        if (order.getStatus() != InspectionStatus.REINSPECTING) {
            throw new BusinessValidationException("当前状态为「" + order.getStatus().getLabel()
                    + "」，只有复检中的单据可以登记复检结果");
        }
        if (request.getNote() == null || request.getNote().isBlank()) {
            throw new BusinessValidationException("复检不通过必须填写不通过原因，便于维修人员追溯");
        }
        return transition(order, InspectionAction.REINSPECTION_FAIL, InspectionStatus.SUBMITTED,
                request.getHandler(), request.getNote(), true);
    }

    /**
     * 判定报废：SUBMITTED / MATERIAL_NEEDED / REINSPECTING → CLOSED_SCRAPPED。
     * 器材永久退出可用池和可绑定清单；既有绑定行转为留档不删除，历史流水/送检记录全部保留。
     */
    @Transactional
    public InspectionOrderDetailDTO scrap(Long id, InspectionActionRequestDTO request) {
        InspectionOrder order = lockOpenOrder(id);
        if (request.getNote() == null || request.getNote().isBlank()) {
            throw new BusinessValidationException("判定报废必须填写报废理由，留档备查");
        }

        Equipment equipment = equipmentRepository.findByIdForUpdate(order.getEquipmentId())
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + order.getEquipmentId()));
        if (dispatchRecordRepository.existsByEquipmentIdAndStatus(
                equipment.getId(), DispatchStatus.ISSUED)) {
            // 转入待处理发生在送检提交环节；到达报废时仍有未归还流水属于数据异常，明确拦截
            throw new ConflictException("该器材仍存在未归还的发装流水，不能报废，请先核查现场收回情况");
        }

        int archived = sessionEquipmentRepository.markScrappedByEquipmentId(
                equipment.getId(), BindDispatchStatus.SCRAPPED, BindDispatchStatus.QUARANTINED);
        equipment.setStatus(EquipmentStatus.SCRAPPED);
        equipmentRepository.save(equipment);

        LocalDateTime now = LocalDateTime.now();
        closeOrder(order, InspectionAction.SCRAP, InspectionStatus.CLOSED_SCRAPPED,
                request.getHandler(), request.getNote(), now);

        log.warn("器材报废: 送检单#{} 器材{} 留档绑定{}行 处理人{} 理由={}",
                order.getId(), equipment.getId(), archived, request.getHandler(), request.getNote());
        return getDetail(order.getId());
    }

    // ==================== 内部方法 ====================

    private InspectionOrder lockOpenOrder(Long id) {
        InspectionOrder order = inspectionOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessValidationException("送检单不存在，ID: " + id));
        if (!order.getStatus().isOpen()) {
            throw new BusinessValidationException("送检单 #" + id + " 已关闭（"
                    + order.getStatus().getLabel() + "），历史记录不能再修改；如需处理请新建送检单");
        }
        return order;
    }

    private InspectionOrderDetailDTO transition(InspectionOrder order, InspectionAction action,
                                                InspectionStatus to, String operator, String note,
                                                boolean saveConclusion) {
        InspectionStatus from = order.getStatus();
        order.setStatus(to);
        order.setHandler(operator.trim());
        if (saveConclusion) {
            order.setConclusion(note);
        }
        order.setUpdateTime(LocalDateTime.now());
        inspectionOrderRepository.save(order);
        appendEvent(order, action, from, to, operator.trim(), note, LocalDateTime.now());
        log.info("送检单状态变更: #{} {} -> {} 操作={} 处理人={}",
                order.getId(), from, to, action, operator.trim());
        return getDetail(order.getId());
    }

    private void closeOrder(InspectionOrder order, InspectionAction action, InspectionStatus closedStatus,
                            String operator, String note, LocalDateTime now) {
        InspectionStatus from = order.getStatus();
        order.setStatus(closedStatus);
        order.setHandler(operator.trim());
        order.setConclusion(note);
        order.setOpenKey(null);
        order.setUpdateTime(now);
        inspectionOrderRepository.save(order);
        appendEvent(order, action, from, closedStatus, operator.trim(), note, now);
    }

    private void appendEvent(InspectionOrder order, InspectionAction action,
                             InspectionStatus from, InspectionStatus to,
                             String operator, String note, LocalDateTime time) {
        InspectionEvent event = new InspectionEvent();
        event.setInspectionOrderId(order.getId());
        event.setEquipmentId(order.getEquipmentId());
        event.setAction(action);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperator(operator);
        event.setNote(note);
        event.setEventTime(time);
        inspectionEventRepository.save(event);
    }

    private String openKey(Long equipmentId) {
        return OPEN_KEY_PREFIX + equipmentId;
    }

    private String defaultNote(String note, String fallback) {
        return (note == null || note.isBlank()) ? fallback : note;
    }

    private Map<Long, Equipment> loadEquipmentMap(List<Long> equipmentIds) {
        if (equipmentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return equipmentRepository.findAllById(equipmentIds).stream()
                .collect(Collectors.toMap(Equipment::getId, Function.identity()));
    }

    private InspectionOrderDTO toOrderDTO(InspectionOrder o, Equipment equipment) {
        InspectionOrderDTO dto = new InspectionOrderDTO();
        dto.setId(o.getId());
        dto.setEquipmentId(o.getEquipmentId());
        dto.setReporter(o.getReporter());
        dto.setProblemDescription(o.getProblemDescription());
        dto.setSubmitTime(o.getSubmitTime());
        dto.setStatus(o.getStatus());
        dto.setStatusLabel(o.getStatus().getLabel());
        dto.setHandler(o.getHandler());
        dto.setConclusion(o.getConclusion());
        dto.setUpdateTime(o.getUpdateTime());
        if (equipment != null) {
            dto.setEquipmentCode(equipment.getEquipmentCode());
            dto.setEquipmentName(equipment.getName());
        }
        return dto;
    }

    private InspectionEventDTO toEventDTO(InspectionEvent e) {
        InspectionEventDTO dto = new InspectionEventDTO();
        dto.setId(e.getId());
        dto.setInspectionOrderId(e.getInspectionOrderId());
        dto.setEquipmentId(e.getEquipmentId());
        dto.setAction(e.getAction());
        dto.setActionLabel(e.getAction().getLabel());
        dto.setFromStatus(e.getFromStatus());
        dto.setFromStatusLabel(e.getFromStatus() != null ? e.getFromStatus().getLabel() : null);
        dto.setToStatus(e.getToStatus());
        dto.setToStatusLabel(e.getToStatus().getLabel());
        dto.setOperator(e.getOperator());
        dto.setNote(e.getNote());
        dto.setEventTime(e.getEventTime());
        return dto;
    }

    /** 器材是否存在未关闭送检单（绑定/发装等路径的守卫） */
    public Optional<InspectionOrder> findOpenOrder(Long equipmentId) {
        return inspectionOrderRepository.findByEquipmentIdAndOpenKeyIsNotNull(equipmentId);
    }

    /** 器材是否曾被送检（删除器材时守卫：有送检历史的不允许物理删除） */
    public boolean hasAnyOrder(Long equipmentId) {
        return inspectionOrderRepository.existsByEquipmentId(equipmentId);
    }

    /** 状态集合工具：报废/送检中均不可进入可绑定清单 */
    public static final Set<EquipmentStatus> NOT_BINDABLE =
            Set.of(EquipmentStatus.INSPECTION, EquipmentStatus.SCRAPPED);
}
