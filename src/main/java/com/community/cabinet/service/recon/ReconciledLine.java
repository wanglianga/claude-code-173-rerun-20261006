package com.community.cabinet.service.recon;

/** 一条对账后的净拿取商品行 */
public record ReconciledLine(
        Long channelId,
        String sku,
        Long batchId,        // 无法确定批次时为 null
        int qty,
        boolean uncertain,   // true = 证据不足, 订单需人工复核
        String basis         // 判定依据描述, 进客服证据
) {}
