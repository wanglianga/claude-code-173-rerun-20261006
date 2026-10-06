package com.community.cabinet.service.recon;

import java.time.LocalDate;

/** 货道上某批次的当前库存(对账时用于 FEFO 指定批次) */
public record ChannelStock(
        Long batchId,
        Long channelId,
        String sku,
        LocalDate expiryDate,
        int onShelfQty
) {}
