package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.Stocktake;
import com.community.cabinet.domain.entity.StocktakeItem;
import com.community.cabinet.service.StocktakeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "盘点", description = "建盘点单/录入实盘数与差异原因/确认调整")
@RestController
@RequestMapping("/api/stocktakes")
public class StocktakeController {

    private final StocktakeService service;

    public StocktakeController(StocktakeService service) { this.service = service; }

    public record CreateReq(@NotBlank String cabinetCode, String note) {}
    public record CountReq(@NotNull Integer countedQty, String reason, String note) {}

    @PostMapping("/create")
    public Stocktake create(@Valid @RequestBody CreateReq req) {
        return service.create(req.cabinetCode(), StaffContext.staffId(), req.note());
    }

    @PostMapping("/items/{itemId}/count")
    public StocktakeItem count(@PathVariable Long itemId, @Valid @RequestBody CountReq req) {
        return service.count(itemId, req.countedQty(), req.reason(), req.note());
    }

    @PostMapping("/{id}/confirm")
    public Stocktake confirm(@PathVariable Long id) { return service.confirm(id); }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) { return service.detail(id); }
}
