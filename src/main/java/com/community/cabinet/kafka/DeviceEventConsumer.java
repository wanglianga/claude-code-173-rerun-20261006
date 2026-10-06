package com.community.cabinet.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 消费柜机上报主题。处理失败不提交偏移(ack-mode=record 抛出即重试);
 * 业务级脏数据由统一入口抛 IllegalArgumentException, 这里捕获后落错误日志,
 * 避免毒消息无限阻塞分区。
 */
@Component
public class DeviceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeviceEventConsumer.class);

    private final DeviceEventIngestService ingestService;

    public DeviceEventConsumer(DeviceEventIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @KafkaListener(topics = Topics.DEVICE_EVENTS, groupId = "${spring.kafka.consumer.group-id}")
    public void onDeviceEvent(@Payload String json,
                              @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
        try {
            String result = ingestService.ingest(json);
            log.debug("设备事件处理完成 partition={} result={}", partition, result);
        } catch (IllegalArgumentException e) {
            log.error("设备事件格式非法, 跳过: {} | payload={}", e.getMessage(), json);
        } catch (Exception e) {
            log.error("设备事件处理失败, 将重试: {}", e.getMessage(), e);
            throw e;
        }
    }
}
