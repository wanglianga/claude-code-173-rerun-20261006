package com.community.cabinet.kafka;

public final class Topics {
    private Topics() {}
    /** 设备上报(柜机 -> 服务) */
    public static final String DEVICE_EVENTS = "cabinet.device.events";
    /** 业务领域事件(服务 -> 下游) */
    public static final String BIZ_EVENTS = "cabinet.biz.events";
}
