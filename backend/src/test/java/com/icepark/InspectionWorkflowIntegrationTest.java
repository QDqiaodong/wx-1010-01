package com.icepark;

import com.icepark.dto.InspectionActionRequestDTO;
import com.icepark.dto.InspectionCreateRequestDTO;
import com.icepark.dto.InspectionOrderDTO;
import com.icepark.dto.IssueRequestDTO;
import com.icepark.entity.Equipment;
import com.icepark.entity.InspectionOrder;
import com.icepark.entity.Session;
import com.icepark.entity.SessionEquipment;
import com.icepark.enums.BindDispatchStatus;
import com.icepark.enums.DispatchStatus;
import com.icepark.enums.EquipmentStatus;
import com.icepark.enums.InspectionStatus;
import com.icepark.enums.SessionStatus;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.EquipmentDispatchRecordRepository;
import com.icepark.repository.EquipmentRepository;
import com.icepark.repository.InspectionActionLogRepository;
import com.icepark.repository.InspectionOrderRepository;
import com.icepark.repository.SessionEquipmentRepository;
import com.icepark.repository.SessionRepository;
import com.icepark.service.EquipmentDispatchService;
import com.icepark.service.InspectionService;
import com.icepark.service.SessionEquipmentService;
import com.icepark.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 器材送检台全链路验收：重复送检、维修退回、复检不通过、报废不可用、
 * 送检期间归还/转入待处理、旧页面并发冲突、复检后重新放行、操作痕迹可查。
 * 使用 H2(MySQL 模式) 验证真实的唯一索引与行锁行为，不打桩。
 */
@SpringBootTest
class InspectionWorkflowIntegrationTest {

    @Autowired private InspectionService inspectionService;
    @Autowired private EquipmentDispatchService dispatchService;
    @Autowired private SessionService sessionService;
    @Autowired private SessionEquipmentService sessionEquipmentService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private SessionEquipmentRepository sessionEquipmentRepository;
    @Autowired private EquipmentDispatchRecordRepository dispatchRecordRepository;
    @Autowired private InspectionOrderRepository inspectionOrderRepository;
    @Autowired private InspectionActionLogRepository actionLogRepository;

    private Long sessionId;
    private Long bindingId;

    @BeforeEach
    void setUp() {
        actionLogRepository.deleteAll();
        inspectionOrderRepository.deleteAll();
        dispatchRecordRepository.deleteAll();
        sessionEquipmentRepository.deleteAll();
        equipmentRepository.deleteAll();
        sessionRepository.deleteAll();

        Session session = new Session();
        session.setSessionCode("S-" + System.nanoTime());
        session.setSessionName("测试场次");
        session.setStartTime(LocalDateTime.now().minusHours(1));
        session.setEndTime(LocalDateTime.now().plusHours(2));
        session.setChildRatio(BigDecimal.ZERO);
        session.setTeenRatio(BigDecimal.ZERO);
        session.setAdultRatio(BigDecimal.ONE);
        session.setStatus(SessionStatus.IN_PROGRESS);
        sessionId = sessionRepository.save(session).getId();
    }

    private Long createEquipment(String codeSuffix, EquipmentStatus status) {
        Equipment equipment = new Equipment();
        equipment.setEquipmentCode("E-" + codeSuffix + "-" + System.nanoTime());
        equipment.setName("极寒滑冰鞋");
        equipment.setFrostResistanceSpec("适用温度 -20℃ ~ 5℃");
        equipment.setAgeGroup(com.icepark.enums.AgeGroup.ADULT);
        equipment.setCategory("冰面辅助器材");
        equipment.setStatus(status);
        Long id = equipmentRepository.save(equipment).getId();

        SessionEquipment binding = new SessionEquipment();
        binding.setSessionId(sessionId);
        binding.setEquipmentId(id);
        binding.setTargetAgeGroup(com.icepark.enums.AgeGroup.ADULT);
        binding.setDispatchStatus(BindDispatchStatus.AVAILABLE);
        bindingId = sessionEquipmentRepository.save(binding).getId();
        return id;
    }

    private InspectionCreateRequestDTO createReq(String description) {
        InspectionCreateRequestDTO req = new InspectionCreateRequestDTO();
        req.setProblemDescription(description);
        req.setReporter("发现人甲");
        return req;
    }

    private InspectionActionRequestDTO action(String handler, String remark) {
        InspectionActionRequestDTO req = new InspectionActionRequestDTO();
        req.setHandler(handler);
        req.setRemark(remark);
        return req;
    }

    private IssueRequestDTO issueReq(Long equipmentId) {
        IssueRequestDTO req = new IssueRequestDTO();
        req.setEquipmentId(equipmentId);
        req.setVisitorName("游客" + System.nanoTime());
        req.setVisitorAgeGroup("ADULT");
        req.setTemperature(new BigDecimal("-10"));
        req.setOperator("发装员");
        return req;
    }

    /** 在架器材送检 -> 发装/绑定都拒绝 -> 复检通过后恢复 */
    @Test
    void inspection_blocksIssueAndBind_untilReinspectionPass() {
        Long equipmentId = createEquipment("shelf", EquipmentStatus.IN_USE);

        InspectionOrderDTO order = inspectionService.createInspection(equipmentId, createReq("冰刀有裂纹"));
        assertEquals(InspectionStatus.SUBMITTED, order.getStatus());
        assertTrue(order.isOpen());

        // 资产状态、绑定行一致冻结
        assertEquals(EquipmentStatus.INSPECTION, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.PENDING,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());

        // 发装被后端拒绝（即使旧页面还停留在可发装视图）
        ConflictException issueEx = assertThrows(ConflictException.class,
                () -> dispatchService.issue(sessionId, issueReq(equipmentId)));
        assertTrue(issueEx.getMessage().contains("送检"), issueEx.getMessage());

        // 绑定到新场次被拒绝
        Long otherSession = createSecondSession();
        ConflictException bindEx = assertThrows(ConflictException.class,
                () -> sessionEquipmentService.bindEquipment(otherSession, equipmentId, "ADULT"));
        assertTrue(bindEx.getMessage().contains("送检"), bindEx.getMessage());

        // 维修 -> 复检通过
        inspectionService.submitReinspection(order.getId(), action("维修员乙", "更换冰刀"));
        InspectionOrderDTO passed = inspectionService.passReinspection(
                order.getId(), action("复检员丙", "复检合格"));
        assertEquals(InspectionStatus.PASSED, passed.getStatus());
        assertFalse(passed.isOpen());

        // 回到可用池：仍被本场次绑定 -> IN_USE + 在架，可立即发装
        assertEquals(EquipmentStatus.IN_USE, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.AVAILABLE,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
        assertEquals(DispatchStatus.ISSUED,
                dispatchService.issue(sessionId, issueReq(equipmentId)).getStatus());
    }

    /** 同一件器材不能同时存在两张未关闭送检单：并发登记只有一张成功 */
    @Test
    void duplicateInspection_concurrent_onlyOneOpenOrder() throws Exception {
        Long equipmentId = createEquipment("dup", EquipmentStatus.IN_USE);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    inspectionService.createInspection(equipmentId, createReq("并发异常登记"));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                    assertTrue(e.getMessage().contains("送检"), e.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, success.get(), "只能有一张未关闭送检单");
        assertEquals(threads - 1, conflict.get(), "其余必须收到明确的重复送检冲突提示");
        long openCount = inspectionOrderRepository.findAll().stream()
                .filter(o -> o.getStatus().isOpen()).count();
        assertEquals(1, openCount);
    }

    /** 已报废器材不能再送检 */
    @Test
    void scrappedEquipment_cannotBeInspectedAgain() {
        Long equipmentId = createEquipment("scrap", EquipmentStatus.IN_USE);
        Long orderId = inspectionService.createInspection(equipmentId, createReq("严重变形")).getId();
        inspectionService.scrap(orderId, action("维修员乙", "无法修复，报废"));
        assertEquals(EquipmentStatus.SCRAPPED, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> inspectionService.createInspection(equipmentId, createReq("再试一次")));
        assertTrue(ex.getMessage().contains("报废"), ex.getMessage());
    }

    /** 维修退回补充材料 -> 补充后重新提交，原始问题描述不变，痕迹各一条 */
    @Test
    void requestInfo_thenResubmit_keepsHistory() {
        Long equipmentId = createEquipment("info", EquipmentStatus.AVAILABLE);
        InspectionOrderDTO order = inspectionService.createInspection(equipmentId, createReq("轮子异响"));

        inspectionService.requestInfo(order.getId(), action("维修员乙", "请补充异响录音与采购批次"));
        assertEquals(InspectionStatus.INFO_NEEDED,
                inspectionService.getOrder(order.getId()).getStatus());

        inspectionService.resubmit(order.getId(), action("发现人甲", "已补传录音，批次 B2026-03"));
        InspectionOrderDTO reloaded = inspectionService.getOrder(order.getId());
        assertEquals(InspectionStatus.SUBMITTED, reloaded.getStatus());
        assertEquals("轮子异响", reloaded.getProblemDescription(), "原始问题描述不能被补充材料覆盖");

        List<String> actions = reloaded.getActionLogs().stream().map(l -> l.getActionType().name()).toList();
        assertEquals(List.of("CREATE", "REQUEST_INFO", "RESUBMIT"), actions);
    }

    /** 复检不通过退回维修，可再次提交复检；每次结论都留痕 */
    @Test
    void reinspectionFail_returnsToRepair_canReinspect() {
        Long equipmentId = createEquipment("fail", EquipmentStatus.AVAILABLE);
        Long orderId = inspectionService.createInspection(equipmentId, createReq("卡扣松动")).getId();

        inspectionService.submitReinspection(orderId, action("维修员乙", "紧了一次"));
        inspectionService.failReinspection(orderId, action("复检员丙", "受力测试仍松动"));
        assertEquals(InspectionStatus.SUBMITTED, inspectionService.getOrder(orderId).getStatus());
        assertEquals(EquipmentStatus.INSPECTION, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());

        // 不通过原因是必填
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> inspectionService.failReinspection(orderId, action("复检员丙", "  ")));
        assertTrue(ex.getMessage().contains("不通过原因"));

        // 维修后再次复检，这次通过
        inspectionService.submitReinspection(orderId, action("维修员乙", "更换卡扣总成"));
        inspectionService.passReinspection(orderId, action("复检员丙", "复测合格"));
        assertEquals(InspectionStatus.PASSED, inspectionService.getOrder(orderId).getStatus());
        // 器材仍绑定在场次上：资产为使用中、绑定行恢复在架
        assertEquals(EquipmentStatus.IN_USE, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.AVAILABLE,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());

        List<String> actions = inspectionService.getOrder(orderId).getActionLogs().stream()
                .map(l -> l.getActionType().name()).toList();
        assertEquals(List.of("CREATE", "SUBMIT_REINSPECTION", "REINSPECTION_FAIL",
                "SUBMIT_REINSPECTION", "REINSPECTION_PASS"), actions);
    }

    /** 报废后：不可发装、不可绑定、从可绑定清单消失，但历史送检单与流水仍可查 */
    @Test
    void scrap_removesFromBindableButKeepsHistory() {
        Long equipmentId = createEquipment("dead", EquipmentStatus.IN_USE);
        Long orderId = inspectionService.createInspection(equipmentId, createReq("结构性断裂")).getId();
        inspectionService.scrap(orderId, action("维修员乙", "无维修价值"));

        assertEquals(EquipmentStatus.SCRAPPED, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        ConflictException issueEx = assertThrows(ConflictException.class,
                () -> dispatchService.issue(sessionId, issueReq(equipmentId)));
        assertTrue(issueEx.getMessage().contains("报废"), issueEx.getMessage());

        Long otherSession = createSecondSession();
        ConflictException bindEx = assertThrows(ConflictException.class,
                () -> sessionEquipmentService.bindEquipment(otherSession, equipmentId, "ADULT"));
        assertTrue(bindEx.getMessage().contains("报废"), bindEx.getMessage());

        // 历史完整可查
        InspectionOrderDTO historical = inspectionService.getOrder(orderId);
        assertEquals(InspectionStatus.SCRAPPED, historical.getStatus());
        assertEquals("结构性断裂", historical.getProblemDescription());
        assertEquals("无维修价值", historical.getConclusion());
        assertNotNull(historical.getCloseTime());
        assertTrue(historical.getActionLogs().size() >= 2);
    }

    /** 待归还状态不允许直接报废 */
    @Test
    void scrap_whilePendingReturn_isRejected() {
        Long equipmentId = createEquipment("pend", EquipmentStatus.IN_USE);
        dispatchService.issue(sessionId, issueReq(equipmentId));
        Long orderId = inspectionService.createInspection(equipmentId, createReq("游客使用中发现异常")).getId();
        assertEquals(InspectionStatus.PENDING_RETURN, inspectionService.getOrder(orderId).getStatus());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> inspectionService.scrap(orderId, action("维修员乙", "想直接报废")));
        assertTrue(ex.getMessage().contains("待归还"), ex.getMessage());
    }

    /** 送检期间游客按原流水正常归还：流水闭环、单据自动进维修队列、资产保持送检中 */
    @Test
    void returnDuringInspection_advancesOrderAndKeepsEquipmentInspection() {
        Long equipmentId = createEquipment("ret", EquipmentStatus.IN_USE);
        var issued = dispatchService.issue(sessionId, issueReq(equipmentId));
        Long orderId = inspectionService.createInspection(equipmentId, createReq("游客报异常")).getId();

        var returned = dispatchService.returnEquipment(sessionId, issued.getId(), "归还员丁");
        assertEquals(DispatchStatus.RETURNED, returned.getStatus());

        InspectionOrderDTO order = inspectionService.getOrder(orderId);
        assertEquals(InspectionStatus.SUBMITTED, order.getStatus(), "归还后单据应自动进入维修队列");
        assertEquals(EquipmentStatus.INSPECTION, equipmentRepository.findById(equipmentId).orElseThrow().getStatus(),
                "资产状态不能被归还复位成可用");
        assertEquals(BindDispatchStatus.PENDING,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus(),
                "绑定行应重新冻结，不能立即再发装");
        assertThrows(ConflictException.class, () -> dispatchService.issue(sessionId, issueReq(equipmentId)));

        // 痕迹包含"送检期间游客归还"且关联流水
        boolean hasReturnTrace = order.getActionLogs().stream()
                .anyMatch(l -> l.getActionType().name().equals("RETURN_WHILE_PENDING")
                        && l.getDispatchRecordId() != null && l.getDispatchRecordId().equals(issued.getId()));
        assertTrue(hasReturnTrace);
    }

    /** 游客无法归还 -> 转入待处理：流水闭环（PENDING_TRANSFER）、单据进维修队列、无悬空 */
    @Test
    void transferOutstanding_closesRecordAndAdvancesOrder() {
        Long equipmentId = createEquipment("trans", EquipmentStatus.IN_USE);
        var issued = dispatchService.issue(sessionId, issueReq(equipmentId));
        Long orderId = inspectionService.createInspection(equipmentId, createReq("游客称器材损坏不愿还")).getId();

        InspectionOrderDTO moved = inspectionService.transferOutstanding(
                orderId, action("现场员戊", "游客离场未归还"));
        assertEquals(InspectionStatus.SUBMITTED, moved.getStatus());

        var record = dispatchRecordRepository.findById(issued.getId()).orElseThrow();
        assertEquals(DispatchStatus.PENDING_TRANSFER, record.getStatus());
        assertNull(record.getOutstandingKey(), "转入待处理必须释放未归还互斥键");
        assertEquals(BindDispatchStatus.PENDING,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());

        // 场次可以正常结束：不会再兜底这条流水，也没有悬空记录
        var ended = sessionService.endSession(sessionId);
        assertEquals("ENDED", ended.getStatus());
        assertEquals(DispatchStatus.PENDING_TRANSFER,
                dispatchRecordRepository.findById(issued.getId()).orElseThrow().getStatus());
        assertEquals(EquipmentStatus.INSPECTION, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());

        // 之后维修报废走得通
        inspectionService.scrap(orderId, action("维修员乙", "确认损坏报废"));
        assertEquals(EquipmentStatus.SCRAPPED, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
    }

    /** 场次结束兜底收回时器材正待归还：单据进维修队列，资产不被错误复位为可用 */
    @Test
    void endSession_withPendingReturnInspection_advancesOrder_noDangling() {
        Long equipmentId = createEquipment("auto", EquipmentStatus.IN_USE);
        var issued = dispatchService.issue(sessionId, issueReq(equipmentId));
        Long orderId = inspectionService.createInspection(equipmentId, createReq("巡检发现")).getId();

        sessionService.endSession(sessionId);

        assertEquals(DispatchStatus.AUTO_CLOSED,
                dispatchRecordRepository.findById(issued.getId()).orElseThrow().getStatus());
        assertEquals(InspectionStatus.SUBMITTED, inspectionService.getOrder(orderId).getStatus());
        assertEquals(EquipmentStatus.INSPECTION, equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.PENDING,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
        assertFalse(dispatchService.sessionHasOutstanding(sessionId));
    }

    /** 旧页面并发：单据已被别人提交复检/报废后，迟到的状态动作不能覆盖，收到 409 */
    @Test
    void staleAction_afterOrderMoved_doesNotOverwrite() {
        Long equipmentId = createEquipment("stale", EquipmentStatus.AVAILABLE);
        Long orderId = inspectionService.createInspection(equipmentId, createReq("页面停留测试")).getId();

        // 别人已经推进到复检中
        inspectionService.submitReinspection(orderId, action("维修员乙", "已维修"));

        // 旧页面仍点"退回补充材料"：必须被拒绝
        ConflictException ex = assertThrows(ConflictException.class,
                () -> inspectionService.requestInfo(orderId, action("迟到的维修员", "补材料")));
        assertTrue(ex.getMessage().contains("刷新"), ex.getMessage());

        // 迟到的"复检通过"在 REINSPECTING 上合法；再点一次必须失败
        inspectionService.passReinspection(orderId, action("复检员丙", "通过"));
        assertThrows(ConflictException.class,
                () -> inspectionService.passReinspection(orderId, action("复检员丙", "重复通过")));
        assertEquals(InspectionStatus.PASSED, inspectionService.getOrder(orderId).getStatus());
    }

    /** 复检通过放行后重新送检产生新单据：上一张历史完整保留不被覆盖 */
    @Test
    void reInspectAfterPass_createsNewOrder_historyPreserved() {
        Long equipmentId = createEquipment("again", EquipmentStatus.AVAILABLE);
        Long firstId = inspectionService.createInspection(equipmentId, createReq("第一次问题")).getId();
        inspectionService.submitReinspection(firstId, action("维修员乙", null));
        inspectionService.passReinspection(firstId, action("复检员丙", "第一次通过"));

        Long secondId = inspectionService.createInspection(equipmentId, createReq("第二次问题")).getId();
        assertNotEquals(firstId, secondId);

        InspectionOrderDTO first = inspectionService.getOrder(firstId);
        assertEquals(InspectionStatus.PASSED, first.getStatus());
        assertEquals("第一次问题", first.getProblemDescription());
        assertEquals("第一次通过", first.getConclusion());

        List<InspectionOrderDTO> history = inspectionService.listOrders(equipmentId, null, null);
        assertEquals(2, history.size());
    }

    /** 现场视图/绑定/列表状态一致性：送检后 items 体现冻结与单号 */
    @Test
    void views_showConsistentInspectionState() {
        Long equipmentId = createEquipment("view", EquipmentStatus.IN_USE);
        Long orderId = inspectionService.createInspection(equipmentId, createReq("一致性检查")).getId();

        var items = dispatchService.listSessionItems(sessionId);
        var item = items.stream().filter(i -> i.getEquipmentId().equals(equipmentId)).findFirst().orElseThrow();
        assertEquals(BindDispatchStatus.PENDING, item.getDispatchStatus());
        assertEquals(EquipmentStatus.INSPECTION, item.getEquipmentStatus());
        assertEquals(orderId, item.getInspectionOrderId());

        // 复检通过后视图恢复在架
        inspectionService.submitReinspection(orderId, action("维修员乙", null));
        inspectionService.passReinspection(orderId, action("复检员丙", null));
        var items2 = dispatchService.listSessionItems(sessionId);
        var item2 = items2.stream().filter(i -> i.getEquipmentId().equals(equipmentId)).findFirst().orElseThrow();
        assertEquals(BindDispatchStatus.AVAILABLE, item2.getDispatchStatus());
        assertEquals(EquipmentStatus.IN_USE, item2.getEquipmentStatus());
        assertNull(item2.getInspectionOrderId());
    }

    /** 未关闭单据可按 openOnly 过滤查询，全部操作痕迹按器材可追溯 */
    @Test
    void queries_openOnlyAndTraceByEquipment() {
        Long e1 = createEquipment("q1", EquipmentStatus.AVAILABLE);
        Long e2 = createEquipment("q2", EquipmentStatus.AVAILABLE);
        Long o1 = inspectionService.createInspection(e1, createReq("未关闭")).getId();
        Long o2 = inspectionService.createInspection(e2, createReq("会关闭")).getId();
        inspectionService.submitReinspection(o2, action("维修员乙", null));
        inspectionService.passReinspection(o2, action("复检员丙", null));

        List<InspectionOrderDTO> openOnly = inspectionService.listOrders(null, null, true);
        assertTrue(openOnly.stream().anyMatch(o -> o.getId().equals(o1)));
        assertTrue(openOnly.stream().noneMatch(o -> o.getId().equals(o2)));

        // 按器材查痕迹
        assertEquals(3, actionLogRepository.findByEquipmentIdOrderByActionTimeDescIdDesc(e2).size());
        // o1 仍占用 open_key：直接造第二张未关闭单会被唯一索引挡住
        assertThrows(Exception.class, () -> {
            InspectionOrder duplicate = new InspectionOrder();
            duplicate.setEquipmentId(e1);
            duplicate.setProblemDescription("绕过服务层插库");
            duplicate.setReporter("x");
            duplicate.setReportTime(LocalDateTime.now());
            duplicate.setStatus(InspectionStatus.SUBMITTED);
            duplicate.setOpenKey("inspect:" + e1);
            inspectionOrderRepository.saveAndFlush(duplicate);
        });
    }

    private Long createSecondSession() {
        Session s2 = new Session();
        s2.setSessionCode("S2-" + System.nanoTime());
        s2.setSessionName("平行场次");
        s2.setStartTime(LocalDateTime.now());
        s2.setEndTime(LocalDateTime.now().plusHours(1));
        s2.setChildRatio(BigDecimal.ZERO);
        s2.setTeenRatio(BigDecimal.ZERO);
        s2.setAdultRatio(BigDecimal.ONE);
        s2.setStatus(SessionStatus.IN_PROGRESS);
        return sessionRepository.save(s2).getId();
    }
}
