package com.community.cabinet.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConfig {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.kafka.auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic deviceTopic() {
        return new NewTopic(Topics.DEVICE_EVENTS, 3, (short) 1);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.kafka.auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic bizTopic() {
        return new NewTopic(Topics.BIZ_EVENTS, 3, (short) 1);
    }

    @Bean
    public EventPublisher eventPublisher(KafkaTemplate<String, String> template, ObjectMapper mapper,
                                         org.springframework.core.env.Environment env) {
        boolean enabled = env.getProperty("app.kafka.publish-enabled", Boolean.class, true);
        return new EventPublisher(template, mapper, enabled);
    }

    /** 测试/无 Kafka 场景的便捷构造 */
    public static Map<String, Object> quickPayload(Object... kv) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i + 1]);
        }
        return m;
    }
}
