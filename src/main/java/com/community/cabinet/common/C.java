package com.community.cabinet.common;

/**
 * 领域状态常量。数据库以 varchar + check 约束存储, 实体字段直接使用这些常量。
 */
public final class C {
    private C() {}

    // 角色
    public static final String ROLE_ROOT = "ROOT";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String ROLE_FINANCE = "FINANCE";
    public static final String ROLE_CS = "CUSTOMER_SERVICE";
    public static final String ROLE_REPLENISHER = "REPLENISHER";
    public static final String ROLE_SUPPLIER = "SUPPLIER";

    // 温层
    public static final String TEMP_AMBIENT = "AMBIENT";
    public static final String TEMP_CHILLED = "CHILLED";
    public static final String TEMP_FROZEN = "FROZEN";

    // 批次状态
    public static final String BATCH_IN_WAREHOUSE = "IN_WAREHOUSE";
    public static final String BATCH_ON_SHELF = "ON_SHELF";
    public static final String BATCH_NEAR_EXPIRY = "NEAR_EXPIRY";
    public static final String BATCH_QUARANTINED = "QUARANTINED";
    public static final String BATCH_REMOVED = "REMOVED";
    public static final String BATCH_SCRAPPED = "SCRAPPED";
    public static final String BATCH_SETTLED = "SETTLED";

    // 库存流水原因
    public static final String L_REPLENISH = "REPLENISH";
    public static final String L_SALE = "SALE";
    public static final String L_SALE_REVERSED = "SALE_REVERSED";
    public static final String L_RETURN_TO_SHELF = "RETURN_TO_SHELF";
    public static final String L_JAM_RECOVER = "JAM_RECOVER";
    public static final String L_WRONG_CHANNEL_MOVE = "WRONG_CHANNEL_MOVE";
    public static final String L_NEAR_EXPIRY_MARK = "NEAR_EXPIRY_MARK";
    public static final String L_REMOVE_EXPIRY = "REMOVE_EXPIRY";
    public static final String L_TEMP_QUARANTINE = "TEMP_QUARANTINE";
    public static final String L_TEMP_RELEASE = "TEMP_RELEASE";
    public static final String L_SCRAP = "SCRAP";
    public static final String L_STOCKTAKE_ADJUST = "STOCKTAKE_ADJUST";

    // 识别对账结果
    public static final String R_PICK = "PICK";
    public static final String R_RETURN = "RETURN";
    public static final String R_UNCERTAIN = "UNCERTAIN";
    public static final String R_IGNORED = "IGNORED";

    // 订单/明细状态
    public static final String ORDER_PENDING = "PENDING";
    public static final String ORDER_CONFIRMED = "CONFIRMED";
    public static final String ORDER_UNCERTAIN = "UNCERTAIN";
    public static final String ORDER_DISPUTED = "DISPUTED";
    public static final String ORDER_REFUNDED = "REFUNDED";
    public static final String ORDER_PARTIAL_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String ORDER_CANCELLED = "CANCELLED";

    public static final String ITEM_CHARGED = "CHARGED";
    public static final String ITEM_REVERSED_RETURN = "REVERSED_RETURN";
    public static final String ITEM_JAM_EXCLUDED = "JAM_EXCLUDED";
    public static final String ITEM_REFUNDED = "REFUNDED";

    // 会话
    public static final String SESSION_OPEN = "OPEN";
    public static final String SESSION_CLOSED = "CLOSED";
    public static final String SESSION_DISPUTED = "DISPUTED";
    public static final String SETTLE_PENDING = "PENDING";
    public static final String SETTLE_SETTLED = "SETTLED";
    public static final String SETTLE_ADJUSTED = "ADJUSTED";
    public static final String SETTLE_REFUNDED = "REFUNDED";

    // 告警
    public static final String ALERT_TEMP = "TEMP_ABNORMAL";
    public static final String ALERT_NEAR = "NEAR_EXPIRY";
    public static final String ALERT_EXPIRED = "EXPIRED";
    public static final String ALERT_JAM = "JAM";
    public static final String ALERT_MISMATCH = "STOCK_MISMATCH";
    public static final String ALERT_WRONG_CHANNEL = "WRONG_CHANNEL";
    public static final String ALERT_LOW_STOCK = "LOW_STOCK";
    public static final String ALERT_UNCERTAIN_ORDER = "UNCERTAIN_ORDER";
}
