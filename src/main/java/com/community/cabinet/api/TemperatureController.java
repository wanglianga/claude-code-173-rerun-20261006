package com.community.cabinet.api;

import com.community.cabinet.domain.entity.TemperatureLog;
import com.community.cabinet.service.TemperatureService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "温控", description = "温度采样/异常隔离/复核解除")
@RestController
@RequestMapping("/api/temperature")
public class TemperatureController {

    private final TemperatureService service;

    public TemperatureController(TemperatureService service) { this.service = service; }

    public record TempReq(@NotBlank String cabinetCode, @NotNull BigDecimal tempC, String sensor) {}
    public record ReleaseReq(@NotNull Long batchId, @NotNull Long channelId, @NotNull Integer qty, String note) {}

    @PostMapping("/record")
    public TemperatureLog record(@Valid @RequestBody TempReq req) {
        return service.record(req.cabinetCode(), req.tempC(), req.sensor());
    }

    @GetMapping("/cabinets/{cabinetCode}/recent")
    public List<TemperatureLog> recent(@PathVariable String cabinetCode) {
        return service.recentLogs(cabinetCode);
    }

    @PostMapping("/alarms/{alarmId}/quarantine")
    public Object quarantine(@PathVariable Long alarmId) {
        return service.quarantineCabinet(alarmId);
    }

    @PostMapping("/alarms/{alarmId}/release")
    public void release(@PathVariable Long alarmId, @Valid @RequestBody ReleaseReq req) {
        service.release(alarmId, req.batchId(), req.channelId(), req.qty(),
                com.community.cabinet.common.StaffContext.staffId(), req.note());
    }
}
