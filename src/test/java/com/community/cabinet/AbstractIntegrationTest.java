package com.community.cabinet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 集成测试基类: H2(PG 兼容) + Flyway 建表 + 嵌入式 Kafka。
 * 相同配置的子类共享同一个 Spring 上下文。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"cabinet.device.events", "cabinet.biz.events"})
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;
}
