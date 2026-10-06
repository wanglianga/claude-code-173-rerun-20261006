package com.community.cabinet.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

/** 领域事件发布(发送失败仅记录日志, 不阻塞主事务外流程) */
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public EventPublisher(KafkaTemplate<String, String> template, ObjectMapper mapper, boolean enabled) {
        this.template = template;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    public void publish(String type, String key, Map<String, Object> payload) {
        if (!enabled) {
            log.debug("Kafka 发布已禁用, 跳过事件 type={}", type);
            return;
        }
        try {
            Map<String, Object> envelope = new java.util.HashMap<>(payload);
            envelope.put("eventType", type);
            envelope.putIfAbsent("occurredAt", java.time.Instant.now().toString());
            String json = mapper.writeValueAsString(envelope);
            template.send(Topics.BIZ_EVENTS, key, json).whenComplete((r, ex) -> {
                if (ex != null) {
                    log.warn("业务事件发送失败 type={} key={}: {}", type, key, ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("业务事件序列化失败 type={}: {}", type, e.getMessage());
        }
    }
}
