package com.community.cabinet.api;

import com.community.cabinet.kafka.DeviceEventIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "设备网关", description = "柜机事件上报(HTTP 入口, 与 Kafka 主题等价); 使用 X-Device-Key 鉴权")
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final DeviceEventIngestService ingestService;

    public DeviceController(DeviceEventIngestService ingestService) {
        this.ingestService = ingestService;
    }

    public record DeviceEvent(@NotBlank String eventId, @NotBlank String eventType,
                              String cabinetCode, String occurredAt, Map<String, Object> data) {}

    @Operation(summary = "上报设备事件(DOOR_OPEN/DOOR_CLOSE/RECOGNITION/TEMPERATURE/JAM)")
    @PostMapping("/events")
    public Map<String, String> event(@RequestBody String rawJson) {
        return Map.of("result", ingestService.ingest(rawJson));
    }
}
