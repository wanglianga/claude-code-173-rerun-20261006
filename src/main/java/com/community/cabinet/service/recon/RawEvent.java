package com.community.cabinet.service.recon;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 设备上报的单条原始识别事件 */
public record RawEvent(
        Integer eventNo,
        LocalDateTime occurredAt,
        String modality,      // WEIGHT / VISION / RFID
        String action,        // PICK / RETURN / DETECT
        Long channelId,
        String sku,
        Integer weightDeltaG, // 货架重量变化: 拿起为负, 放回为正
        BigDecimal confidence
) {}
