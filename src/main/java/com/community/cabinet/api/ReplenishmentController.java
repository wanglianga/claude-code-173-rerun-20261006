package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.Replenishment;
import com.community.cabinet.domain.entity.ReplenishmentItem;
import com.community.cabinet.service.ReplenishmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "补货", description = "到货登记/批次货道录入/放错货道检测/确认上架/纠正")
@RestController
@RequestMapping("/api/replenishments")
public class ReplenishmentController {

    private final ReplenishmentService service;

    public ReplenishmentController(ReplenishmentService service) { this.service = service; }

    public record ArriveReq(@NotBlank String cabinetCode, String photoUrl, String note) {}
    public record ItemReq(@NotBlank String batchNo, @NotBlank String channelCode, @NotNull Integer qty) {}
    public record ConfirmReq(Boolean forceIssue) {}
    public record CorrectReq(@NotBlank String channelCode) {}

    @PostMapping("/arrive")
    public Replenishment arrive(@Valid @RequestBody ArriveReq req) {
        return service.arrive(req.cabinetCode(), StaffContext.staffId(), req.photoUrl(), req.note());
    }

    @PostMapping("/{id}/items")
    public ReplenishmentItem addItem(@PathVariable Long id, @Valid @RequestBody ItemReq req) {
        return service.addItem(id, req.batchNo(), req.channelCode(), req.qty());
    }

    @PostMapping("/{id}/confirm")
    public Replenishment confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmReq req) {
        return service.confirm(id, req != null && Boolean.TRUE.equals(req.forceIssue()));
    }

    @PostMapping("/items/{itemId}/correct")
    public ReplenishmentItem correct(@PathVariable Long itemId, @Valid @RequestBody CorrectReq req) {
        return service.correctItem(itemId, req.channelCode());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) { return service.detail(id); }
}
