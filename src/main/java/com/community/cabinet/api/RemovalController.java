package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.RemovalRecord;
import com.community.cabinet.service.ExpiryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "临期/过期下架", description = "扫描生成下架任务/处置下架(下架暂存/复核回架/报损)")
@RestController
@RequestMapping("/api/removals")
public class RemovalController {

    private final ExpiryService service;

    public RemovalController(ExpiryService service) { this.service = service; }

    public record HandleReq(@NotBlank String action, String note) {}

    @PostMapping("/scan")
    public Map<String, Integer> scan() { return Map.of("createdTasks", service.scan()); }

    @GetMapping("/pending")
    public List<RemovalRecord> pending() { return service.pending(); }

    @PostMapping("/{id}/handle")
    public RemovalRecord handle(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody
    HandleReq req) {
        return service.handle(id, req.action(), StaffContext.staffId(), req.note());
    }
}
