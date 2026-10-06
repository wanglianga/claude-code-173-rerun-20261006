package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.Settlement;
import com.community.cabinet.service.SettlementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@Tag(name = "财务结算", description = "按账期/供应商依据真实售卖与报损生成并确认结算单")
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final SettlementService service;

    public FinanceController(SettlementService service) { this.service = service; }

    public record GenerateReq(@NotNull Long supplierId, @NotNull LocalDate periodStart,
                              @NotNull LocalDate periodEnd) {}
    public record ConfirmReq(String note) {}

    @PostMapping("/settlements/generate")
    public Settlement generate(@Valid @RequestBody GenerateReq req) {
        return service.generate(req.supplierId(), req.periodStart(), req.periodEnd(),
                StaffContext.staffId());
    }

    @PostMapping("/settlements/{id}/confirm")
    public Settlement confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmReq req) {
        return service.confirm(id, StaffContext.staffId(), req == null ? null : req.note());
    }

    @GetMapping("/settlements/{id}")
    public Map<String, Object> detail(@PathVariable Long id) { return service.detail(id); }
}
