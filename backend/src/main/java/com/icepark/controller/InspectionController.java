package com.icepark.controller;

import com.icepark.dto.InspectionActionRequestDTO;
import com.icepark.dto.InspectionCreateRequestDTO;
import com.icepark.dto.InspectionOrderDTO;
import com.icepark.enums.InspectionStatus;
import com.icepark.service.InspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 器材送检台：登记送检、转入待处理、退回补充材料、补充后重提、提交复检、
 * 复检通过/不通过、判定报废，以及送检单与操作痕迹查询。
 *
 * 所有状态并发约束均在后端/数据库保证，旧页面提交只会收到 409 明确提示，不会覆盖新状态。
 */
@RestController
@RequestMapping("/api/inspection")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    /** 查询送检单：可按器材、状态过滤；openOnly=true 只看未关闭的单据 */
    @GetMapping("/orders")
    public ResponseEntity<List<InspectionOrderDTO>> listOrders(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean openOnly) {
        InspectionStatus statusEnum = parseStatus(status);
        return ResponseEntity.ok(inspectionService.listOrders(equipmentId, statusEnum, openOnly));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<InspectionOrderDTO> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(inspectionService.getOrder(id));
    }

    /** 现场工作人员登记送检（问题描述 + 发现人） */
    @PostMapping("/equipment/{equipmentId}")
    public ResponseEntity<InspectionOrderDTO> createInspection(
            @PathVariable Long equipmentId,
            @Valid @RequestBody InspectionCreateRequestDTO request) {
        return ResponseEntity.ok(inspectionService.createInspection(equipmentId, request));
    }

    /** 游客确认无法归还：器材随送检单转入待处理，原发装流水闭环 */
    @PostMapping("/orders/{id}/transfer-pending")
    public ResponseEntity<InspectionOrderDTO> transferPending(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.transferOutstanding(id, request));
    }

    /** 维修人员退回补充材料 */
    @PostMapping("/orders/{id}/request-info")
    public ResponseEntity<InspectionOrderDTO> requestInfo(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.requestInfo(id, request));
    }

    /** 发现人补充材料后重新提交 */
    @PostMapping("/orders/{id}/resubmit")
    public ResponseEntity<InspectionOrderDTO> resubmit(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.resubmit(id, request));
    }

    /** 维修完成，提交复检 */
    @PostMapping("/orders/{id}/submit-reinspection")
    public ResponseEntity<InspectionOrderDTO> submitReinspection(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.submitReinspection(id, request));
    }

    /** 复检通过：器材回到可用池 */
    @PostMapping("/orders/{id}/reinspection-pass")
    public ResponseEntity<InspectionOrderDTO> reinspectionPass(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.passReinspection(id, request));
    }

    /** 复检不通过：退回维修队列，必须填不通过原因（留痕） */
    @PostMapping("/orders/{id}/reinspection-fail")
    public ResponseEntity<InspectionOrderDTO> reinspectionFail(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.failReinspection(id, request));
    }

    /** 判定报废 */
    @PostMapping("/orders/{id}/scrap")
    public ResponseEntity<InspectionOrderDTO> scrap(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionService.scrap(id, request));
    }

    private InspectionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InspectionStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("送检单状态不合法：" + raw);
        }
    }
}
