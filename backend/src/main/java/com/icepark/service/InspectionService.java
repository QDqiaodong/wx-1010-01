package com.icepark.service;

import com.icepark.dto.InspectionActionLogDTO;
import com.icepark.dto.InspectionActionRequestDTO;
import com.icepark.dto.InspectionCreateRequestDTO;
import com.icepark.dto.InspectionOrderDTO;
import com.icepark.entity.Equipment;
import com.icepark.entity.EquipmentDispatchRecord;
import com.icepark.entity.InspectionActionLog;
import com.icepark.entity.InspectionOrder;
import com.icepark.entity.Session;
import com.icepark.enums.BindDispatchStatus;
import com.icepark.enums.DispatchStatus;
import com.icepark.enums.EquipmentStatus;
import com.icepark.enums.InspectionActionType;
import com.icepark.enums.InspectionStatus;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.EquipmentDispatchRecordRepository;
import com.icepark.repository.EquipmentRepository;
import com.icepark.repository.InspectionActionLogRepository;
import com.icepark.repository.InspectionOrderRepository;
import com.icepark.repository.SessionEquipmentRepository;
import com.icepark.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 器材送检台：送检登记 → （待归还/转入待处理）→ 维修 → 退回补充材料/复检 → 通过放行/报废。
 *
 * 不变量（全部在后端 + 数据库保证，不依赖前端按钮禁用）：
 * 1. 同一件器材任何时刻至多一张未关闭送检单：器材行悲观锁串行化 + open_key 唯一索引兜底；
 * 2. 送检期间器材资产状态为 INSPECTION，绑定行为"送检冻结"，发装/新绑定一律拒绝；
 * 3. 已在游客手上的器材允许先登记（待归还）：游客按原流水正常归还或显式转入待处理，
 *    不会产生"既不能归还又不能维修"的悬空记录；
 * 4. 每次状态变化都追加操作痕迹；单据本身、问题描述、发装流水均不可被后续复检覆盖；
 * 5. 复检通过器材才回可用池；报废保留全部历史，且从可绑定/可发装清单消失。
 *
 * 加锁顺序与发装链路统一：先场次行锁、再器材行锁，杜绝交叉持锁死锁。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InspectionService {

    public static final List<InspectionStatus> OPEN_STATUSES = List.of(
            InspectionStatus.PENDING_RETURN,
            InspectionStatus.SUBMITTED,
            InspectionStatus.INFO_NEEDED,
            InspectionStatus.REINSPECTING);

    /** 可判报废的状态：已在库的维修阶段；待归还（器材还在游客手上）不允许直接报废 */
    private static final List<InspectionStatus> SCRAPPABLE_STATUSES = List.of(
            InspectionStatus.SUBMITTED,
            InspectionStatus.INFO_NEEDED,
            InspectionStatus.REINSPECTING);

    private static final String OPEN_KEY_PREFIX = "inspect:";

    private final InspectionOrderRepository inspectionOrderRepository;
    private final InspectionActionLogRepository actionLogRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentDispatchRecordRepository dispatchRecordRepository;
    private final SessionEquipmentRepository sessionEquipmentRepository;
    private final SessionRepository sessionRepository;

    // ==================== 现场登记送检 ====================

    /**
     * 现场工作人员登记送检。
     * 器材在架：单据直接进维修队列，所有场次绑定行冻结；
     * 器材已发给游客：单据为"待归还"，游客仍可按原流水归还（或由工作人员转入待处理）。
     */
    @Transactional
    public InspectionOrderDTO createInspection(Long equipmentId, InspectionCreateRequestDTO request) {
        // 锁器材行：并发送检在此串行，第二个请求一定能看到第一张单
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + equipmentId));

        if (equipment.getStatus() == EquipmentStatus.SCRAPPED) {
            throw new ConflictException("器材「" + equipment.getEquipmentCode() + "」已报废，不能再送检");
        }

        inspectionOrderRepository.findFirstByEquipmentIdAndStatusInOrderByIdDesc(equipmentId, OPEN_STATUSES)
                .ifPresent(existing -> {
                    throw new ConflictException("器材「" + equipment.getEquipmentCode() + "」已存在未关闭送检单（单号 #"
                            + existing.getId() + "，状态：" + existing.getStatus().getLabel()
                            + "），不能重复送检");
                });

        EquipmentDispatchRecord outstanding = dispatchRecordRepository
                .findFirstByEquipmentIdAndStatusOrderByIdDesc(equipmentId, DispatchStatus.ISSUED)
                .orElse(null);

        LocalDateTime now = LocalDateTime.now();
        InspectionOrder order = new InspectionOrder();
        order.setEquipmentId(equipmentId);
        order.setProblemDescription(request.getProblemDescription().trim());
        order.setReporter(request.getReporter().trim());
        order.setReportTime(now);
        order.setStatus(outstanding != null ? InspectionStatus.PENDING_RETURN : InspectionStatus.SUBMITTED);
        order.setOpenKey(openKey(equipmentId));
        if (outstanding != null) {
            order.setDispatchRecordId(outstanding.getId());
            order.setSessionId(outstanding.getSessionId());
        }

        try {
            order = inspectionOrderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // open_key 唯一索引兜底：极端并发下应用层判定与插入之间插入了另一张单
            log.warn("送检命中未关闭单据唯一约束，equipmentId={}", equipmentId, e);
            throw new ConflictException("器材「" + equipment.getEquipmentCode()
                    + "」已被其他工作人员送检且单据尚未关闭，不能重复送检");
        }

        // 资产状态置为送检中：列表/绑定/发装各页面读到一致状态
        equipment.setStatus(EquipmentStatus.INSPECTION);
        equipmentRepository.save(equipment);

        if (outstanding == null) {
            // 在架器材：冻结它在所有场次的绑定行（CAS，已领用的行不动）
            sessionEquipmentRepository.markPendingIfAvailable(
                    equipmentId, BindDispatchStatus.AVAILABLE, BindDispatchStatus.PENDING);
        }

        writeLog(order.getId(), equipmentId, InspectionActionType.CREATE,
                null, order.getStatus(), order.getReporter(), order.getProblemDescription(), null);

        log.info("送检登记: 单号{} 器材{} 发现人{} 待归还={}",
                order.getId(), equipmentId, order.getReporter(), outstanding != null);
        return toDTO(order, equipment, true);
    }

    /**
     * 游客确认无法归还时，把仍在其手上的器材随送检单"转入待处理"：
     * 发装流水置 PENDING_TRANSFER 并释放未归还互斥键，绑定行冻结，单据进入维修队列。
     * 必须在场次事务（持有场次行锁）语义下调用，这里自行按"先场次后器材"加锁。
     */
    @Transactional
    public InspectionOrderDTO transferOutstanding(Long orderId, InspectionActionRequestDTO request) {
        InspectionOrder order = getOrderEntity(orderId);
        if (order.getStatus() != InspectionStatus.PENDING_RETURN) {
            throw new BusinessValidationException("单据当前为「" + order.getStatus().getLabel()
                    + "」，只有待归还的送检单可以转入待处理");
        }
        EquipmentDispatchRecord record = dispatchRecordRepository.findById(order.getDispatchRecordId())
                .orElseThrow(() -> new BusinessValidationException("关联的发装流水不存在，无法转入待处理"));

        // 统一加锁顺序：先场次行，再器材行
        Session session = sessionRepository.findByIdForUpdate(record.getSessionId())
                .orElseThrow(() -> new BusinessValidationException("场次不存在，ID: " + record.getSessionId()));
        Equipment equipment = equipmentRepository.findByIdForUpdate(order.getEquipmentId()).orElseThrow();

        int transferred = dispatchRecordRepository.transferOutstanding(
                record.getId(), request.getHandler().trim(), LocalDateTime.now());
        if (transferred == 0) {
            throw new ConflictException("器材已被归还或已由场次兜底收回，本次转入未执行，请刷新查看最新状态");
        }
        sessionEquipmentRepository.markPendingIfIssued(
                record.getSessionEquipmentId(), BindDispatchStatus.ISSUED, BindDispatchStatus.PENDING);

        int moved = inspectionOrderRepository.advanceFromPendingReturn(
                orderId, InspectionStatus.PENDING_RETURN, InspectionStatus.SUBMITTED);
        if (moved == 0) {
            throw new ConflictException("送检单状态已变化，本次转入未执行，请刷新后重试");
        }

        writeLog(orderId, order.getEquipmentId(), InspectionActionType.TRANSFER_PENDING,
                InspectionStatus.PENDING_RETURN, InspectionStatus.SUBMITTED,
                request.getHandler().trim(),
                request.getRemark() != null ? request.getRemark().trim() : "游客未归还，器材转入待处理",
                record.getId());
        log.warn("送检转入待处理: 单号{} 场次{} 流水{} 器材{} 原游客{}",
                orderId, session.getId(), record.getId(), order.getEquipmentId(), record.getVisitorName());

        return getOrder(orderId);
    }

    // ==================== 维修台动作 ====================

    /** 维修人员退回补充材料：单据回到发现人处，等待补充后重新提交 */
    @Transactional
    public InspectionOrderDTO requestInfo(Long orderId, InspectionActionRequestDTO request) {
        String reason = requireRemark(request.getRemark(), "退回补充材料必须填写需要补充的内容");
        InspectionOrder order = lockAndReload(orderId);
        int updated = inspectionOrderRepository.transitionIfStatus(
                orderId, InspectionStatus.SUBMITTED, InspectionStatus.INFO_NEEDED, request.getHandler().trim());
        if (updated == 0) {
            throw staleConflict(order);
        }
        writeLog(orderId, order.getEquipmentId(), InspectionActionType.REQUEST_INFO,
                InspectionStatus.SUBMITTED, InspectionStatus.INFO_NEEDED,
                request.getHandler().trim(), reason, null);
        return getOrder(orderId);
    }

    /** 发现人补充材料后重新提交进维修队列（原始问题描述不变，补充内容作为痕迹追加） */
    @Transactional
    public InspectionOrderDTO resubmit(Long orderId, InspectionActionRequestDTO request) {
        String supplement = requireRemark(request.getRemark(), "重新提交必须填写补充材料内容");
        InspectionOrder order = lockAndReload(orderId);
        int updated = inspectionOrderRepository.transitionIfStatus(
                orderId, InspectionStatus.INFO_NEEDED, InspectionStatus.SUBMITTED, request.getHandler().trim());
        if (updated == 0) {
            throw staleConflict(order);
        }
        writeLog(orderId, order.getEquipmentId(), InspectionActionType.RESUBMIT,
                InspectionStatus.INFO_NEEDED, InspectionStatus.SUBMITTED,
                request.getHandler().trim(), supplement, null);
        return getOrder(orderId);
    }

    /** 维修完成，提交复检 */
    @Transactional
    public InspectionOrderDTO submitReinspection(Long orderId, InspectionActionRequestDTO request) {
        InspectionOrder order = lockAndReload(orderId);
        int updated = inspectionOrderRepository.transitionIfStatus(
                orderId, InspectionStatus.SUBMITTED, InspectionStatus.REINSPECTING, request.getHandler().trim());
        if (updated == 0) {
            throw staleConflict(order);
        }
        writeLog(orderId, order.getEquipmentId(), InspectionActionType.SUBMIT_REINSPECTION,
                InspectionStatus.SUBMITTED, InspectionStatus.REINSPECTING,
                request.getHandler().trim(),
                request.getRemark() != null ? request.getRemark().trim() : null, null);
        return getOrder(orderId);
    }

    /**
     * 复检不通过：单据退回维修队列，可维修后再次提交复检。
     * 不通过原因与后续每一次复检都各自留痕，任何结论都不覆盖历史。
     */
    @Transactional
    public InspectionOrderDTO failReinspection(Long orderId, InspectionActionRequestDTO request) {
        String reason = requireRemark(request.getRemark(), "复检不通过必须填写不通过原因");
        InspectionOrder order = lockAndReload(orderId);
        int updated = inspectionOrderRepository.transitionIfStatus(
                orderId, InspectionStatus.REINSPECTING, InspectionStatus.SUBMITTED, request.getHandler().trim());
        if (updated == 0) {
            throw staleConflict(order);
        }
        writeLog(orderId, order.getEquipmentId(), InspectionActionType.REINSPECTION_FAIL,
                InspectionStatus.REINSPECTING, InspectionStatus.SUBMITTED,
                request.getHandler().trim(), reason, null);
        log.info("复检不通过: 单号{} 器材{} 原因{}", orderId, order.getEquipmentId(), reason);
        return getOrder(orderId);
    }

    /** 复检通过：单据关闭，器材重新回到可用池（仍被场次绑定的恢复在架，否则资产状态复位可用） */
    @Transactional
    public InspectionOrderDTO passReinspection(Long orderId, InspectionActionRequestDTO request) {
        InspectionOrder order = lockAndReload(orderId);
        String conclusion = request.getRemark() != null ? request.getRemark().trim() : "复检通过，器材恢复可用";

        int closed = inspectionOrderRepository.closeIfInStatuses(
                orderId, List.of(InspectionStatus.REINSPECTING), InspectionStatus.PASSED,
                request.getHandler().trim(), conclusion, LocalDateTime.now());
        if (closed == 0) {
            throw staleConflict(order);
        }

        reactivateEquipment(order.getEquipmentId(), request.getHandler().trim());
        writeLog(orderId, order.getEquipmentId(), InspectionActionType.REINSPECTION_PASS,
                InspectionStatus.REINSPECTING, InspectionStatus.PASSED,
                request.getHandler().trim(), conclusion, null);
        log.info("复检通过放行: 单号{} 器材{}", orderId, order.getEquipmentId());
        return getOrder(orderId);
    }

    /** 判定报废：单据关闭，器材置 SCRAPPED，历史全保留，从可绑定/可发装清单消失 */
    @Transactional
    public InspectionOrderDTO scrap(Long orderId, InspectionActionRequestDTO request) {
        String reason = requireRemark(request.getRemark(), "判定报废必须填写报废原因");
        InspectionOrder order = lockAndReload(orderId);
        Equipment equipment = equipmentRepository.findById(order.getEquipmentId()).orElseThrow();

        int closed = inspectionOrderRepository.closeIfInStatuses(
                orderId, SCRAPPABLE_STATUSES, InspectionStatus.SCRAPPED,
                request.getHandler().trim(), reason, LocalDateTime.now());
        if (closed == 0) {
            if (order.getStatus() == InspectionStatus.PENDING_RETURN) {
                throw new ConflictException("器材仍在游客手上（待归还），请先完成归还或转入待处理后再判定报废");
            }
            throw staleConflict(order);
        }

        equipment.setStatus(EquipmentStatus.SCRAPPED);
        equipmentRepository.save(equipment);
        writeLog(orderId, order.getEquipmentId(), InspectionActionType.SCRAP,
                order.getStatus(), InspectionStatus.SCRAPPED,
                request.getHandler().trim(), reason, null);
        log.warn("器材报废: 单号{} 器材{} 操作人{} 原因{}",
                orderId, order.getEquipmentId(), request.getHandler(), reason);
        return getOrder(orderId);
    }

    // ==================== 发装链路回调（必须在外层持有场次锁的事务内调用） ====================

    /**
     * 发装流水终结（正常归还 / 场次结束兜底收回）后回调：
     * 若该器材存在"待归还"送检单，单据推进到维修队列并把刚复位的绑定行重新冻结。
     * 由 EquipmentDispatchService 在同一事务内调用。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onOutstandingRecordClosed(EquipmentDispatchRecord record, InspectionActionType action) {
        List<InspectionOrder> pending = inspectionOrderRepository
                .findByEquipmentIdAndStatusIn(record.getEquipmentId(),
                        List.of(InspectionStatus.PENDING_RETURN));
        if (pending.isEmpty()) {
            return false;
        }
        InspectionOrder order = pending.get(0);
        int moved = inspectionOrderRepository.advanceFromPendingReturn(
                order.getId(), InspectionStatus.PENDING_RETURN, InspectionStatus.SUBMITTED);
        if (moved == 0) {
            return false;
        }
        // 归还/兜底刚把绑定行复位在架，送检未关闭，立即重新冻结，前端刷新即见"送检冻结"
        sessionEquipmentRepository.markPendingIfAvailable(
                record.getEquipmentId(), BindDispatchStatus.AVAILABLE, BindDispatchStatus.PENDING);
        String operator = record.getReturnOperator() != null ? record.getReturnOperator() : "系统";
        writeLog(order.getId(), record.getEquipmentId(), action,
                InspectionStatus.PENDING_RETURN, InspectionStatus.SUBMITTED,
                operator, action.getLabel(), record.getId());
        log.info("送检单待归还结束: 单号{} 器材{} 动作{}", order.getId(), record.getEquipmentId(), action);
        return true;
    }

    /** 器材是否存在未关闭送检单（发装/绑定守卫用） */
    @Transactional(readOnly = true)
    public boolean hasOpenInspection(Long equipmentId) {
        return inspectionOrderRepository.existsByEquipmentIdAndStatusIn(equipmentId, OPEN_STATUSES);
    }

    /** 批量查询一组器材当前的未关闭送检单（发装台/绑定页现场视图用） */
    @Transactional(readOnly = true)
    public List<InspectionOrder> findOpenOrdersByEquipmentIds(List<Long> equipmentIds) {
        if (equipmentIds == null || equipmentIds.isEmpty()) {
            return List.of();
        }
        return inspectionOrderRepository.findByEquipmentIdInAndStatusIn(equipmentIds, OPEN_STATUSES);
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<InspectionOrderDTO> listOrders(Long equipmentId, InspectionStatus status, Boolean openOnly) {
        List<InspectionOrder> orders;
        if (equipmentId != null) {
            orders = inspectionOrderRepository.findByEquipmentIdOrderByReportTimeDescIdDesc(equipmentId);
            if (status != null) {
                orders = orders.stream().filter(o -> o.getStatus() == status).toList();
            } else if (Boolean.TRUE.equals(openOnly)) {
                orders = orders.stream().filter(o -> o.getStatus().isOpen()).toList();
            }
        } else if (status != null) {
            orders = inspectionOrderRepository.findByStatusInOrderByReportTimeDescIdDesc(List.of(status));
        } else if (Boolean.TRUE.equals(openOnly)) {
            orders = inspectionOrderRepository.findByStatusInOrderByReportTimeDescIdDesc(OPEN_STATUSES);
        } else {
            orders = inspectionOrderRepository.findAllByOrderByReportTimeDescIdDesc();
        }
        return assemble(orders, true);
    }

    @Transactional(readOnly = true)
    public InspectionOrderDTO getOrder(Long id) {
        InspectionOrder order = getOrderEntity(id);
        Equipment equipment = equipmentRepository.findById(order.getEquipmentId()).orElse(null);
        return toDTO(order, equipment, true);
    }

    // ==================== 内部方法 ====================

    /** 复检通过后器材重新放行：冻结的绑定行恢复在架；无绑定时资产复位可用 */
    private void reactivateEquipment(Long equipmentId, String operator) {
        Equipment equipment = equipmentRepository.findById(equipmentId).orElse(null);
        if (equipment == null) {
            return;
        }
        int restored = sessionEquipmentRepository.markAvailableIfPending(
                equipmentId, BindDispatchStatus.PENDING, BindDispatchStatus.AVAILABLE);
        boolean stillBound = !sessionEquipmentRepository.findByEquipmentId(equipmentId).isEmpty();
        equipment.setStatus(stillBound ? EquipmentStatus.IN_USE : EquipmentStatus.AVAILABLE);
        equipmentRepository.save(equipment);
        log.info("复检放行资产复位: 器材{} 恢复绑定行{} 资产状态{}",
                equipmentId, restored, equipment.getStatus());
    }

    /** 动作入口：锁器材行（与并发送检/报废/复检互斥），返回最新单据 */
    private InspectionOrder lockAndReload(Long orderId) {
        InspectionOrder order = getOrderEntity(orderId);
        equipmentRepository.findByIdForUpdate(order.getEquipmentId())
                .orElseThrow(() -> new BusinessValidationException("器材不存在，ID: " + order.getEquipmentId()));
        return inspectionOrderRepository.findById(orderId).orElseThrow();
    }

    private InspectionOrder getOrderEntity(Long id) {
        return inspectionOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessValidationException("送检单不存在，ID: " + id));
    }

    private ConflictException staleConflict(InspectionOrder order) {
        InspectionStatus current = inspectionOrderRepository.findById(order.getId())
                .map(InspectionOrder::getStatus).orElse(order.getStatus());
        return new ConflictException("送检单 #" + order.getId() + " 当前状态为「"
                + current.getLabel() + "」，可能已被其他工作人员处理，请刷新后重试（本次操作未覆盖新状态）");
    }

    private String requireRemark(String remark, String message) {
        if (remark == null || remark.trim().isEmpty()) {
            throw new BusinessValidationException(message);
        }
        return remark.trim();
    }

    private void writeLog(Long orderId, Long equipmentId, InspectionActionType actionType,
                          InspectionStatus from, InspectionStatus to,
                          String operator, String remark, Long dispatchRecordId) {
        InspectionActionLog actionLog = new InspectionActionLog();
        actionLog.setOrderId(orderId);
        actionLog.setEquipmentId(equipmentId);
        actionLog.setActionType(actionType);
        actionLog.setFromStatus(from);
        actionLog.setToStatus(to);
        actionLog.setOperator(operator);
        actionLog.setRemark(remark);
        actionLog.setDispatchRecordId(dispatchRecordId);
        actionLog.setActionTime(LocalDateTime.now());
        actionLogRepository.save(actionLog);
    }

    private String openKey(Long equipmentId) {
        return OPEN_KEY_PREFIX + equipmentId;
    }

    private List<InspectionOrderDTO> assemble(List<InspectionOrder> orders, boolean withLogs) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> equipmentIds = orders.stream().map(InspectionOrder::getEquipmentId).distinct().toList();
        Map<Long, Equipment> equipmentMap = equipmentRepository.findAllById(equipmentIds).stream()
                .collect(Collectors.toMap(Equipment::getId, Function.identity()));
        List<InspectionOrderDTO> result = new ArrayList<>(orders.size());
        for (InspectionOrder order : orders) {
            result.add(toDTO(order, equipmentMap.get(order.getEquipmentId()), withLogs));
        }
        return result;
    }

    private InspectionOrderDTO toDTO(InspectionOrder order, Equipment equipment, boolean withLogs) {
        InspectionOrderDTO dto = new InspectionOrderDTO();
        dto.setId(order.getId());
        dto.setEquipmentId(order.getEquipmentId());
        dto.setProblemDescription(order.getProblemDescription());
        dto.setReporter(order.getReporter());
        dto.setReportTime(order.getReportTime());
        dto.setHandler(order.getHandler());
        dto.setStatus(order.getStatus());
        dto.setStatusLabel(order.getStatus().getLabel());
        dto.setOpen(order.getStatus().isOpen());
        dto.setDispatchRecordId(order.getDispatchRecordId());
        dto.setSessionId(order.getSessionId());
        dto.setConclusion(order.getConclusion());
        dto.setCloseTime(order.getCloseTime());
        dto.setCreateTime(order.getCreateTime());
        dto.setUpdateTime(order.getUpdateTime());
        if (equipment != null) {
            dto.setEquipmentCode(equipment.getEquipmentCode());
            dto.setEquipmentName(equipment.getName());
            dto.setCategory(equipment.getCategory());
        }
        if (withLogs) {
            dto.setActionLogs(actionLogRepository
                    .findByOrderIdOrderByActionTimeAscIdAsc(order.getId()).stream()
                    .map(this::toLogDTO).toList());
        }
        return dto;
    }

    private InspectionActionLogDTO toLogDTO(InspectionActionLog l) {
        InspectionActionLogDTO dto = new InspectionActionLogDTO();
        dto.setId(l.getId());
        dto.setOrderId(l.getOrderId());
        dto.setEquipmentId(l.getEquipmentId());
        dto.setActionType(l.getActionType());
        dto.setActionTypeLabel(l.getActionType().getLabel());
        dto.setFromStatus(l.getFromStatus());
        dto.setFromStatusLabel(l.getFromStatus() != null ? l.getFromStatus().getLabel() : null);
        dto.setToStatus(l.getToStatus());
        dto.setToStatusLabel(l.getToStatus().getLabel());
        dto.setOperator(l.getOperator());
        dto.setRemark(l.getRemark());
        dto.setDispatchRecordId(l.getDispatchRecordId());
        dto.setActionTime(l.getActionTime());
        return dto;
    }
}
