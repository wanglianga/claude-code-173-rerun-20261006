package com.community.cabinet.common;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** 业务单号生成(前缀 + 年月日时分秒 + 序列) */
public final class Ids {
    private Ids() {}
    private static final AtomicLong SEQ = new AtomicLong(0);

    public static String gen(String prefix) {
        long n = SEQ.incrementAndGet();
        return prefix + LocalDateTime.now(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", n % 10000);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
