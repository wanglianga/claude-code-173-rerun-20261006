package com.community.cabinet.kafka;

import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.EventLog;
import com.community.cabinet.domain.repository.DoorSessionRepository;
import com.community.cabinet.domain.repository.EventLogRepository;
import com.community.cabinet.service.JamService;
import com.community.cabinet.service.SessionService;
import com.community.cabinet.service.TemperatureService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 设备事件统一入口(HTTP 网关与 Kafka 消费者共用), 以 eventId 幂等去重并归档 event_log。
 * 支持事件: DOOR_OPEN / DOOR_CLOSE / RECOGNITION / TEMPERATURE / JAM
 */
@Service
public class DeviceEventIngestService {

    private static final Logger log = LoggerFactory.getLogger(DeviceEventIngestService.class);

    private final ObjectMapper mapper;
    private final EventLogRepository eventLogRepository;
    private final DoorSessionRepository sessionRepository;
    private final SessionService sessionService;
    private final TemperatureService temperatureService;
    private final JamService jamService;

    public DeviceEventIngestService(ObjectMapper mapper, EventLogRepository eventLogRepository,
                                    DoorSessionRepository sessionRepository,
                                    SessionService sessionService, TemperatureService temperatureService,
                                    JamService jamService) {
        this.mapper = mapper;
        this.eventLogRepository = eventLogRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.temperatureService = temperatureService;
        this.jamService = jamService;
    }

    @Transactional
    public String ingest(String rawJson) {
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("事件 JSON 解析失败: " + e.getMessage());
        }
        String eventId = text(root, "eventId");
        String eventType = text(root, "eventType");
        String cabinetCode = text(root, "cabinetCode");
        if (eventId == null || eventType == null) {
            throw new IllegalArgumentException("eventId/eventType 必填");
        }
        if (eventLogRepository.findByEventId(eventId).isPresent()) {
            log.info("重复设备事件忽略 eventId={}", eventId);
            return "DUPLICATE";
        }
        EventLog logRow = new EventLog();
        logRow.setEventId(eventId);
        logRow.setEventType(eventType);
        logRow.setCabinetCode(cabinetCode);
        if (root.hasNonNull("occurredAt")) {
            try {
                logRow.setOccurredAt(OffsetDateTime.parse(root.get("occurredAt").asText()).toLocalDateTime());
            } catch (Exception ignored) { }
        }
        logRow.setPayload(rawJson);
        logRow.setProcessed(true);
        logRow.setCreatedAt(Ids.now());
        eventLogRepository.save(logRow);

        JsonNode d = root.get("data");
        switch (eventType) {
            case "DOOR_OPEN" -> sessionService.openSession(cabinetCode, text(d, "userId"),
                    text(d, "videoClipUrl"), longVal(d, "clipStartMs"));
            case "RECOGNITION" -> {
                String sessionNo = text(d, "sessionNo");
                if (sessionNo == null) throw new IllegalArgumentException("RECOGNITION 缺少 sessionNo");
                Long sessionId = sessionRepository.findBySessionNo(sessionNo)
                        .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionNo)).getId();
                sessionService.ingestEvent(sessionId, text(d, "modality"), text(d, "action"),
                        text(d, "channelCode"), text(d, "sku"), null,
                        intVal(d, "weightDeltaG"), decimal(d, "confidence"),
                        d == null ? null : d.toString(), intVal(d, "seq", 1));
            }
            case "DOOR_CLOSE" -> sessionService.closeSession(text(d, "sessionNo"),
                    longVal(d, "clipEndMs"));
            case "TEMPERATURE" -> temperatureService.record(cabinetCode,
                    decimal(d, "tempC"), text(d, "sensor"));
            case "JAM" -> jamService.report(cabinetCode, text(d, "channelCode"),
                    text(d, "batchNo"), text(d, "sessionNo"),
                    intVal(d, "qty", 1), text(d, "note"));
            default -> log.warn("未知设备事件类型: {}", eventType);
        }
        return "PROCESSED";
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intVal(JsonNode n, String field) {
        return intVal(n, field, 0);
    }

    private static Integer intVal(JsonNode n, String field, int def) {
        if (n == null || !n.hasNonNull(field)) return def;
        return n.get(field).asInt(def);
    }

    private static Long longVal(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        return n.get(field).asLong();
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        return new BigDecimal(n.get(field).asText());
    }
}
