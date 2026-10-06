package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.JamEvent;
import com.community.cabinet.service.JamService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "卡货", description = "上报与处置卡货(取回上架/报损/忽略)")
@RestController
@RequestMapping("/api/jams")
public class JamController {

    private final JamService service;

    public JamController(JamService service) { this.service = service; }

    public record ReportReq(@NotBlank String cabinetCode, @NotBlank String channelCode,
                            String batchNo, String sessionNo, Integer qty, String note) {}
    public record ResolveReq(@NotBlank String resolution, String note) {}

    @PostMapping("/report")
    public JamEvent report(@Valid @RequestBody ReportReq req) {
        return service.report(req.cabinetCode(), req.channelCode(), req.batchNo(), req.sessionNo(),
                req.qty() == null ? 1 : req.qty(), req.note());
    }

    @PostMapping("/{id}/resolve")
    public JamEvent resolve(@PathVariable Long id, @Valid @RequestBody ResolveReq req) {
        return service.resolve(id, req.resolution(), StaffContext.staffId(), req.note());
    }

    @GetMapping("/open")
    public List<JamEvent> open() { return service.openJams(); }
}
