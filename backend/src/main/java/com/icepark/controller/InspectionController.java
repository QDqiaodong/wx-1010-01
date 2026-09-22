package com.icepark.controller;

import com.icepark.dto.InspectionActionRequestDTO;
import com.icepark.dto.InspectionCreateRequestDTO;
import com.icepark.dto.InspectionOrderDTO;
import com.icepark.dto.InspectionOrderDetailDTO;
import com.icepark.service.InspectionOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 器材送检台：送检单提交、维修处理（退回补材料/报废/提交复检）、复检结论、历史与操作痕迹查询。
 *
 * 状态流：
 *   POST   /api/inspection                        提交送检（可带 forceTransfer 转入待处理）
 *   POST   /api/inspection/{id}/return-materials  维修退回补充材料
 *   POST   /api/inspection/{id}/resubmit          材料补齐重新提交
 *   POST   /api/inspection/{id}/reinspect         维修完成提交复检
 *   POST   /api/inspection/{id}/reinspect/pass    复检通过 → 器材回可用池
 *   POST   /api/inspection/{id}/reinspect/fail    复检不通过 → 退回维修
 *   POST   /api/inspection/{id}/scrap             判定报废 → 退出可绑定清单
 *   GET    /api/inspection?open=true|false        送检单列表（未关闭/已关闭/全部）
 *   GET    /api/inspection/{id}                   单据详情（含按时间排序的操作痕迹）
 *   GET    /api/inspection/equipment/{equipmentId} 某器材的全部送检历史（历史不覆盖，可多次送检）
 */
@RestController
@RequestMapping("/api/inspection")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionOrderService inspectionOrderService;

    @GetMapping
    public ResponseEntity<List<InspectionOrderDTO>> list(@RequestParam(required = false) Boolean open,
                                                         @RequestParam(required = false) Long equipmentId) {
        if (equipmentId != null) {
            return ResponseEntity.ok(inspectionOrderService.listByEquipment(equipmentId));
        }
        return ResponseEntity.ok(inspectionOrderService.listOrders(open));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InspectionOrderDetailDTO> detail(@PathVariable Long id) {
        return ResponseEntity.ok(inspectionOrderService.getDetail(id));
    }

    @GetMapping("/equipment/{equipmentId}")
    public ResponseEntity<List<InspectionOrderDTO>> listByEquipment(@PathVariable Long equipmentId) {
        return ResponseEntity.ok(inspectionOrderService.listByEquipment(equipmentId));
    }

    @PostMapping
    public ResponseEntity<InspectionOrderDetailDTO> submit(
            @Valid @RequestBody InspectionCreateRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.submit(request));
    }

    @PostMapping("/{id}/return-materials")
    public ResponseEntity<InspectionOrderDetailDTO> returnMaterials(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.returnMaterials(id, request));
    }

    @PostMapping("/{id}/resubmit")
    public ResponseEntity<InspectionOrderDetailDTO> resubmit(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.resubmit(id, request));
    }

    @PostMapping("/{id}/reinspect")
    public ResponseEntity<InspectionOrderDetailDTO> submitReinspection(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.submitReinspection(id, request));
    }

    @PostMapping("/{id}/reinspect/pass")
    public ResponseEntity<InspectionOrderDetailDTO> passReinspection(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.passReinspection(id, request));
    }

    @PostMapping("/{id}/reinspect/fail")
    public ResponseEntity<InspectionOrderDetailDTO> failReinspection(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.failReinspection(id, request));
    }

    @PostMapping("/{id}/scrap")
    public ResponseEntity<InspectionOrderDetailDTO> scrap(
            @PathVariable Long id,
            @Valid @RequestBody InspectionActionRequestDTO request) {
        return ResponseEntity.ok(inspectionOrderService.scrap(id, request));
    }
}
