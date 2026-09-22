package com.icepark;

import com.icepark.dto.InspectionActionRequestDTO;
import com.icepark.dto.InspectionCreateRequestDTO;
import com.icepark.dto.IssueRequestDTO;
import com.icepark.dto.SessionDispatchItemDTO;
import com.icepark.entity.Equipment;
import com.icepark.entity.InspectionOrder;
import com.icepark.entity.Session;
import com.icepark.entity.SessionEquipment;
import com.icepark.enums.AgeGroup;
import com.icepark.enums.BindDispatchStatus;
import com.icepark.enums.DispatchStatus;
import com.icepark.enums.EquipmentStatus;
import com.icepark.enums.InspectionAction;
import com.icepark.enums.InspectionStatus;
import com.icepark.enums.SessionStatus;
import com.icepark.dto.InspectionOrderDetailDTO;
import com.icepark.exception.BusinessValidationException;
import com.icepark.exception.ConflictException;
import com.icepark.repository.EquipmentDispatchRecordRepository;
import com.icepark.repository.EquipmentRepository;
import com.icepark.repository.InspectionEventRepository;
import com.icepark.repository.InspectionOrderRepository;
import com.icepark.repository.SessionEquipmentRepository;
import com.icepark.repository.SessionRepository;
import com.icepark.service.EquipmentDispatchService;
import com.icepark.service.InspectionOrderService;
import com.icepark.service.SessionEquipmentService;
import com.icepark.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 器材送检台全链路验收测试：
 * 重复送检拦截、在架/游客使用中送检、转入待处理、维修退回补材料、复检不通过/通过、
 * 报废后不可绑定/不可发装、送检期间场次归还与结束兜底、复检通过重新放行、操作痕迹可查。
 * 使用 H2(MySQL 模式) 验证真实唯一约束/锁行为，不打桩。
 */
@SpringBootTest
class EquipmentInspectionIntegrationTest {

    @Autowired private InspectionOrderService inspectionService;
    @Autowired private EquipmentDispatchService dispatchService;
    @Autowired private SessionService sessionService;
    @Autowired private SessionEquipmentService sessionEquipmentService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private SessionEquipmentRepository sessionEquipmentRepository;
    @Autowired private EquipmentDispatchRecordRepository dispatchRecordRepository;
    @Autowired private InspectionOrderRepository inspectionOrderRepository;
    @Autowired private InspectionEventRepository inspectionEventRepository;

    private Long sessionId;
    private Long equipmentId;
    private Long bindingId;

    @BeforeEach
    void setUp() {
        inspectionEventRepository.deleteAll();
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

        Equipment equipment = new Equipment();
        equipment.setEquipmentCode("E-" + System.nanoTime());
        equipment.setName("极寒滑冰鞋");
        equipment.setFrostResistanceSpec("适用温度 -20℃ ~ 5℃");
        equipment.setAgeGroup(AgeGroup.ADULT);
        equipment.setCategory("冰面辅助器材");
        equipment.setStatus(EquipmentStatus.IN_USE);
        equipmentId = equipmentRepository.save(equipment).getId();

        SessionEquipment binding = new SessionEquipment();
        binding.setSessionId(sessionId);
        binding.setEquipmentId(equipmentId);
        binding.setTargetAgeGroup(AgeGroup.ADULT);
        binding.setDispatchStatus(BindDispatchStatus.AVAILABLE);
        bindingId = sessionEquipmentRepository.save(binding).getId();
    }

    private InspectionCreateRequestDTO submitReq(boolean forceTransfer) {
        InspectionCreateRequestDTO req = new InspectionCreateRequestDTO();
        req.setEquipmentId(equipmentId);
        req.setReporter("巡检员甲");
        req.setProblemDescription("冰刀卡扣松动，现场发现异常");
        req.setForceTransfer(forceTransfer);
        return req;
    }

    private InspectionActionRequestDTO action(String handler, String note) {
        InspectionActionRequestDTO req = new InspectionActionRequestDTO();
        req.setHandler(handler);
        req.setNote(note);
        return req;
    }

    private IssueRequestDTO issueReq() {
        IssueRequestDTO req = new IssueRequestDTO();
        req.setEquipmentId(equipmentId);
        req.setVisitorName("游客" + System.nanoTime());
        req.setVisitorAgeGroup("ADULT");
        req.setTemperature(new BigDecimal("-10"));
        req.setOperator("发装员A");
        return req;
    }

    // ---------- 1. 在架器材送检：立即隔离、不可绑定/发装 ----------

    @Test
    void submit_availableEquipment_quarantinesAndBlocksIssue() {
        InspectionOrderDetailDTO detail = inspectionService.submit(submitReq(false));
        Long orderId = detail.getOrder().getId();

        assertEquals(InspectionStatus.SUBMITTED, detail.getOrder().getStatus());
        assertEquals(EquipmentStatus.INSPECTION,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.QUARANTINED,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
        assertEquals(orderId, detail.getEvents().get(0).getInspectionOrderId());
        assertEquals(InspectionAction.SUBMIT, detail.getEvents().get(0).getAction());

        // 发装被拒绝（旧页面停留提交同样挡住）
        ConflictException ex = assertThrows(ConflictException.class,
                () -> dispatchService.issue(sessionId, issueReq()));
        assertTrue(ex.getMessage().contains("送检"), ex.getMessage());

        // 现场视图带一致状态
        SessionDispatchItemDTO item = dispatchService.listSessionItems(sessionId).get(0);
        assertEquals(BindDispatchStatus.QUARANTINED, item.getDispatchStatus());
        assertEquals(EquipmentStatus.INSPECTION, item.getEquipmentStatus());
        assertEquals(orderId, item.getOpenInspectionId());
    }

    // ---------- 2. 重复送检：第二张单据必须失败 ----------

    @Test
    void submit_twice_secondRejected() {
        inspectionService.submit(submitReq(false));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> inspectionService.submit(submitReq(false)));
        assertTrue(ex.getMessage().contains("未关闭") && ex.getMessage().contains("重复送检"), ex.getMessage());
        assertEquals(1, inspectionOrderRepository.count());
    }

    @Test
    void submit_concurrent_onlyOneOrderCreated() throws Exception {
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
                    inspectionService.submit(submitReq(false));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
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

        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, inspectionOrderRepository.count());
    }

    // ---------- 3. 已发给游客：不勾选转入待处理则拒绝；勾选则收回+送检，不留悬空 ----------

    @Test
    void submit_whenIssued_withoutForceTransfer_rejectedAndKeepsReturnPath() {
        dispatchService.issue(sessionId, issueReq());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> inspectionService.submit(submitReq(false)));
        assertTrue(ex.getMessage().contains("未归还") || ex.getMessage().contains("转入待处理"), ex.getMessage());

        // 资产与绑定未被改动，仍可正常归还
        assertEquals(EquipmentStatus.IN_USE,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.ISSUED,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
    }

    @Test
    void submit_whenIssued_forceTransfer_closesRecordAndQuarantines() {
        var issued = dispatchService.issue(sessionId, issueReq());

        InspectionOrderDetailDTO detail = inspectionService.submit(submitReq(true));
        assertEquals(InspectionStatus.SUBMITTED, detail.getOrder().getStatus());

        // 流水转为"转入待处理"，不再悬空；绑定隔离、资产送检中
        var record = dispatchRecordRepository.findById(issued.getId()).orElseThrow();
        assertEquals(DispatchStatus.TRANSFERRED_PENDING, record.getStatus());
        assertNotNull(record.getReturnTime());
        assertTrue(record.getReturnOperator().contains("巡检员甲"));
        assertNull(record.getOutstandingKey());
        assertEquals(BindDispatchStatus.QUARANTINED,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
        assertEquals(EquipmentStatus.INSPECTION,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());

        // 转入待处理后不能再按正常归还
        BusinessValidationException rex = assertThrows(BusinessValidationException.class,
                () -> dispatchService.returnEquipment(sessionId, issued.getId(), "归还员"));
        assertTrue(rex.getMessage().contains("转入待处理"), rex.getMessage());
    }

    // ---------- 4. 送检期间（游客正常归还）场次归还仍可完成 ----------

    @Test
    void returnDuringInspection_normalReturnThenSubmitWorks() {
        var issued = dispatchService.issue(sessionId, issueReq());

        // 另一工作人员先完成归还（游客提前送回）；器材仍绑定本场次，资产保持 IN_USE、绑定回到在架
        dispatchService.returnEquipment(sessionId, issued.getId(), "归还员B");
        assertEquals(EquipmentStatus.IN_USE,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.AVAILABLE,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());

        // 再送检：没有未归还流水，直接隔离
        InspectionOrderDetailDTO detail = inspectionService.submit(submitReq(false));
        assertEquals(InspectionStatus.SUBMITTED, detail.getOrder().getStatus());
        assertEquals(BindDispatchStatus.QUARANTINED,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
    }

    // ---------- 5. 场次结束兜底不复活送检中/报废器材 ----------

    @Test
    void endSession_whileInspected_doesNotResetEquipment() {
        dispatchService.issue(sessionId, issueReq());
        inspectionService.submit(submitReq(true));

        sessionService.endSession(sessionId);
        // 已转入待处理，无 ISSUED 流水；资产保持送检中，绑定保持隔离
        assertEquals(EquipmentStatus.INSPECTION,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.QUARANTINED,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
    }

    // ---------- 6. 维修退回补材料 → 补齐提交 ----------

    @Test
    void returnMaterials_thenResubmit_historyKept() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();

        var returned = inspectionService.returnMaterials(orderId, action("维修工乙", "请补充卡扣探伤照片"));
        assertEquals(InspectionStatus.MATERIAL_NEEDED, returned.getOrder().getStatus());

        BusinessValidationException wrongState = assertThrows(BusinessValidationException.class,
                () -> inspectionService.submitReinspection(orderId, action("维修工乙", "还没补材料")));
        assertTrue(wrongState.getMessage().contains("待维修"));

        var resubmitted = inspectionService.resubmit(orderId, action("巡检员甲", "探伤照片已补齐"));
        assertEquals(InspectionStatus.SUBMITTED, resubmitted.getOrder().getStatus());

        List<com.icepark.entity.InspectionEvent> events =
                inspectionEventRepository.findByInspectionOrderIdOrderByEventTimeAscIdAsc(orderId);
        assertEquals(3, events.size());
        assertEquals(InspectionAction.SUBMIT, events.get(0).getAction());
        assertEquals(InspectionAction.RETURN_MATERIALS, events.get(1).getAction());
        assertEquals(InspectionAction.RESUBMIT, events.get(2).getAction());
    }

    // ---------- 7. 复检不通过退回维修，可再次提交复检（历史不覆盖） ----------

    @Test
    void reinspectFail_goesBackToRepair_thenCanResubmit() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();
        inspectionService.submitReinspection(orderId, action("维修工乙", "已更换卡扣"));

        // 不通过必须给原因
        assertThrows(BusinessValidationException.class,
                () -> inspectionService.failReinspection(orderId, action("复检员丙", "  ")));

        var failed = inspectionService.failReinspection(orderId, action("复检员丙", "低温下仍有松旷"));
        assertEquals(InspectionStatus.SUBMITTED, failed.getOrder().getStatus());
        // 资产仍送检中，未提前放行
        assertEquals(EquipmentStatus.INSPECTION,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());

        // 再次维修提交复检
        var again = inspectionService.submitReinspection(orderId, action("维修工乙", "二次加固完成"));
        assertEquals(InspectionStatus.REINSPECTING, again.getOrder().getStatus());
    }

    // ---------- 8. 复检通过：回可用池、绑定复位、立即能再发装；再送检是新单据 ----------

    @Test
    void reinspectPass_equipmentReturnsToPool_andRebindIssueWorks() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();
        inspectionService.submitReinspection(orderId, action("维修工乙", "已修复"));
        var passed = inspectionService.passReinspection(orderId, action("复检员丙", "复检通过"));

        assertEquals(InspectionStatus.CLOSED_PASSED, passed.getOrder().getStatus());
        assertNull(inspectionOrderRepository.findById(orderId).orElseThrow().getOpenKey());
        // 器材仍绑定本进行中场次：IN_USE；隔离绑定复位在架
        assertEquals(EquipmentStatus.IN_USE,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.AVAILABLE,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());

        // 立即可以发装
        var issued = dispatchService.issue(sessionId, issueReq());
        assertEquals(DispatchStatus.ISSUED, issued.getStatus());

        // 历史单据仍可查，再次送检创建的是新单据（历史不覆盖）
        dispatchService.returnEquipment(sessionId, issued.getId(), "归还员");
        var second = inspectionService.submit(submitReq(false));
        assertNotEquals(orderId, second.getOrder().getId());
        assertEquals(2, inspectionOrderRepository.count());

        // 已关闭单据不能再操作
        BusinessValidationException closed = assertThrows(BusinessValidationException.class,
                () -> inspectionService.submitReinspection(orderId, action("x", "y")));
        assertTrue(closed.getMessage().contains("已关闭"));
    }

    @Test
    void reinspectPass_unboundEquipment_becomesAvailable() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();
        // 模拟没有任何绑定的器材送检（解绑后送检）
        sessionEquipmentRepository.deleteById(bindingId);
        inspectionService.submitReinspection(orderId, action("维修工乙", "已修复"));
        inspectionService.passReinspection(orderId, action("复检员丙", "通过"));

        assertEquals(EquipmentStatus.AVAILABLE,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
    }

    // ---------- 9. 报废：退出可绑定清单、不能发装，历史保留 ----------

    @Test
    void scrap_removesFromBindableList_andBlocksIssue_historyKept() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();

        // 报废必须填理由
        assertThrows(BusinessValidationException.class,
                () -> inspectionService.scrap(orderId, action("主管丁", " ")));

        var scrapped = inspectionService.scrap(orderId, action("主管丁", "结构裂纹无法修复"));
        assertEquals(InspectionStatus.CLOSED_SCRAPPED, scrapped.getOrder().getStatus());
        assertEquals(EquipmentStatus.SCRAPPED,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
        assertEquals(BindDispatchStatus.SCRAPPED,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());

        // 不能发装
        ConflictException iex = assertThrows(ConflictException.class,
                () -> dispatchService.issue(sessionId, issueReq()));
        assertTrue(iex.getMessage().contains("报废"));

        // 不能绑定到新场次
        Long s2 = createSecondSession();
        ConflictException bex = assertThrows(ConflictException.class,
                () -> sessionEquipmentService.bindEquipment(s2, equipmentId, "ADULT"));
        assertTrue(bex.getMessage().contains("报废"));

        // 报废后不能再送检
        ConflictException sex = assertThrows(ConflictException.class,
                () -> inspectionService.submit(submitReq(false)));
        assertTrue(sex.getMessage().contains("已报废"));

        // 历史仍可查询（单据 + 事件）
        var detail = inspectionService.getDetail(orderId);
        assertEquals(InspectionStatus.CLOSED_SCRAPPED, detail.getOrder().getStatus());
        assertTrue(detail.getEvents().stream().anyMatch(e -> e.getAction() == InspectionAction.SCRAP));
    }

    @Test
    void scrap_afterReinspectFail_allowedFromSubmitted() {
        // 复检不通过退回待维修后，可直接报废
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();
        inspectionService.submitReinspection(orderId, action("乙", "修了"));
        inspectionService.failReinspection(orderId, action("丙", "不行"));
        var scrapped = inspectionService.scrap(orderId, action("丁", "维修无价值"));
        assertEquals(InspectionStatus.CLOSED_SCRAPPED, scrapped.getOrder().getStatus());
    }

    // ---------- 10. 状态一致性：送检后新场次自动/手动绑定均跳过，复检通过恢复 ----------

    @Test
    void bindWhileInspection_rejectedWithClearMessage() {
        inspectionService.submit(submitReq(false));
        Long s2 = createSecondSession();

        ConflictException ex = assertThrows(ConflictException.class,
                () -> sessionEquipmentService.bindEquipment(s2, equipmentId, "ADULT"));
        assertTrue(ex.getMessage().contains("送检"), ex.getMessage());
    }

    @Test
    void openOrdersQuery_reflectsLifecycle() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();
        assertTrue(inspectionService.findOpenOrder(equipmentId).isPresent());

        inspectionService.submitReinspection(orderId, action("乙", "修复"));
        inspectionService.passReinspection(orderId, action("丙", "通过"));
        assertTrue(inspectionService.findOpenOrder(equipmentId).isEmpty());
        assertTrue(inspectionService.hasAnyOrder(equipmentId));

        // 列表：未关闭/已关闭分别可查
        assertEquals(0, inspectionService.listOrders(true).size());
        assertEquals(1, inspectionService.listOrders(false).size());
    }

    // ---------- 11. 送检期间场次结束后再复检通过：绑定复位与资产状态仍一致 ----------

    @Test
    void passAfterSessionEnded_restoresBindingConsistently() {
        Long orderId = inspectionService.submit(submitReq(false)).getOrder().getId();
        sessionService.endSession(sessionId);

        inspectionService.submitReinspection(orderId, action("乙", "修复"));
        inspectionService.passReinspection(orderId, action("丙", "通过"));

        // 已结束场次仍保留绑定行，复位在架；器材仍"绑定中"为 IN_USE
        assertEquals(BindDispatchStatus.AVAILABLE,
                sessionEquipmentRepository.findById(bindingId).orElseThrow().getDispatchStatus());
        assertEquals(EquipmentStatus.IN_USE,
                equipmentRepository.findById(equipmentId).orElseThrow().getStatus());
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

    // ---------- 12. 发装 vs 送检 同器材高频竞争：不允许死锁/锁超时，只能有业务性结果 ----------

    @Test
    void concurrentIssueVsInspection_noDeadlock_businessOutcomesOnly() throws Exception {
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                Equipment eq = new Equipment();
                eq.setEquipmentCode("EC-" + System.nanoTime() + "-" + round);
                eq.setName("竞赛器材");
                eq.setFrostResistanceSpec("适用温度 -30℃ ~ 5℃");
                eq.setAgeGroup(AgeGroup.ADULT);
                eq.setCategory("冰面辅助器材");
                eq.setStatus(EquipmentStatus.IN_USE);
                Long eqId = equipmentRepository.save(eq).getId();

                SessionEquipment b = new SessionEquipment();
                b.setSessionId(sessionId);
                b.setEquipmentId(eqId);
                b.setTargetAgeGroup(AgeGroup.ADULT);
                b.setDispatchStatus(BindDispatchStatus.AVAILABLE);
                sessionEquipmentRepository.save(b);

                Long finalEqId = eqId;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<Throwable> nonBusinessErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

                pool.submit(() -> {
                    try {
                        start.await();
                        IssueRequestDTO req = issueReq();
                        req.setEquipmentId(finalEqId);
                        dispatchService.issue(sessionId, req);
                    } catch (ConflictException | BusinessValidationException expected) {
                        // 送检先抢到：发装被业务拒绝，合法结果
                    } catch (Throwable t) {
                        nonBusinessErrors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        start.await();
                        InspectionCreateRequestDTO req = submitReq(true);
                        req.setEquipmentId(finalEqId);
                        inspectionService.submit(req);
                    } catch (ConflictException | BusinessValidationException expected) {
                        // 发装先抢到且未勾选转入…这里勾选了 force，理论上都能转入；重复送检等业务冲突也算合法
                    } catch (Throwable t) {
                        nonBusinessErrors.add(t);
                    } finally {
                        done.countDown();
                    }
                });

                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "第" + round + "轮竞争未在时限内结束（疑似死锁）");
                assertTrue(nonBusinessErrors.isEmpty(),
                        "出现锁超时/死锁等非业务异常: "
                                + nonBusinessErrors.stream().map(Throwable::toString).toList());

                // 不变量：送检中器材绝不允许残留未归还（ISSUED）流水
                Equipment after = equipmentRepository.findById(eqId).orElseThrow();
                if (after.getStatus() == EquipmentStatus.INSPECTION) {
                    assertFalse(dispatchRecordRepository.existsByEquipmentIdAndStatus(eqId, DispatchStatus.ISSUED),
                            "送检中器材存在 ISSUED 流水（悬空记录），第" + round + "轮");
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}
