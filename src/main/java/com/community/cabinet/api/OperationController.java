package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.CategoryAdjustment;
import com.community.cabinet.service.OperationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "运营端", description = "点位销量/损耗/临期率指标与货道品类调整")
@RestController
@RequestMapping("/api/operations")
public class OperationController {

    private final OperationService service;

    public OperationController(OperationService service) { this.service = service; }

    public record AdjustReq(@NotBlank String cabinetCode, @NotBlank String channelCode,
                            String newExpectedSku, String reason, Boolean applyNow) {}

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam String cabinetCode,
                                       @RequestParam(defaultValue = "7") int days) {
        return service.pointMetrics(cabinetCode, days);
    }

    @PostMapping("/category-adjustments")
    public CategoryAdjustment adjust(@Valid @RequestBody AdjustReq req) {
        return service.adjustCategory(req.cabinetCode(), req.channelCode(), req.newExpectedSku(),
                req.reason(), Boolean.TRUE.equals(req.applyNow()), StaffContext.staffId());
    }

    @GetMapping("/category-adjustments")
    public List<CategoryAdjustment> adjustments(@RequestParam String cabinetCode) {
        return service.adjustments(cabinetCode);
    }

    @PostMapping("/low-stock/scan")
    public Map<String, Integer> scanLowStock() {
        return Map.of("alerts", service.scanLowStock());
    }
}
