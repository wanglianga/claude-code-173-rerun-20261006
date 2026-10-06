package com.community.cabinet.service.recon;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecognitionReconcilerTest {

    private final RecognitionReconciler reconciler = new RecognitionReconciler();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 6, 10, 0, 0);

    private ChannelStock stock(Long batch, Long ch, String sku, String expiry, int qty) {
        return new ChannelStock(batch, ch, sku, LocalDate.parse(expiry), qty);
    }

    private RawEvent ev(int no, int sec, String modality, String action, Long ch, String sku,
                        Integer weight, String conf) {
        return new RawEvent(no, T0.plusSeconds(sec), modality, action, ch, sku, weight,
                conf == null ? null : new BigDecimal(conf));
    }

    @Test
    void 拿起又放回_重量与视觉双双配对_不计费() {
        var stocks = List.of(stock(1L, 10L, "SKU-MILK", "2026-10-13", 5));
        var events = List.of(
                ev(1, 1, "WEIGHT", "PICK", 10L, null, -250, null),
                ev(2, 2, "VISION", "PICK", 10L, "SKU-MILK", null, "0.95"),
                ev(3, 5, "VISION", "RETURN", 10L, "SKU-MILK", null, "0.90"),
                ev(4, 6, "WEIGHT", "RETURN", 10L, null, 252, null)
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertFalse(r.uncertain());
        assertTrue(r.lines().isEmpty(), "放回后净拿取应为 0");
    }

    @Test
    void 三模一致_计费并按FEFO选择较早批次() {
        var stocks = List.of(
                stock(100L, 10L, "SKU-MILK", "2026-10-13", 5),
                stock(200L, 10L, "SKU-MILK", "2026-10-08", 3)
        );
        var events = List.of(
                ev(1, 1, "WEIGHT", "PICK", 10L, null, -250, null),
                ev(2, 1, "VISION", "PICK", 10L, "SKU-MILK", null, "0.98"),
                ev(3, 1, "RFID", "PICK", 10L, "SKU-MILK", null, null)
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertFalse(r.uncertain());
        assertEquals(1, r.lines().size());
        ReconciledLine line = r.lines().get(0);
        assertEquals("SKU-MILK", line.sku());
        assertEquals(200L, line.batchId(), "FEFO 应选到期日更早的批次 200");
        assertEquals(1, line.qty());
    }

    @Test
    void 重量与识别冲突_证据不足不自动扣款() {
        var stocks = List.of(stock(100L, 10L, "SKU-MILK", "2026-10-13", 5));
        var events = List.of(
                ev(1, 1, "WEIGHT", "PICK", 10L, null, -500, null),  // 重量 2 件? 引擎按次计数=1
                ev(2, 1, "WEIGHT", "PICK", 10L, null, -500, null),
                ev(3, 1, "VISION", "PICK", 10L, "SKU-MILK", null, "0.98") // 视觉只有 1
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertTrue(r.uncertain());
        assertEquals(1, r.lines().size());
        assertTrue(r.lines().get(0).uncertain());
    }

    @Test
    void 重量无变化但视觉报拿取_疑似误识别_转人工() {
        var stocks = List.of(stock(100L, 10L, "SKU-MILK", "2026-10-13", 5));
        var events = List.of(
                ev(1, 1, "WEIGHT", "DETECT", 10L, null, 0, null),
                ev(2, 2, "VISION", "PICK", 10L, "SKU-MILK", null, "0.90")
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertTrue(r.uncertain(), "重量无锚定不应自动扣款");
        assertEquals(1, r.lines().size());
    }

    @Test
    void 低置信度视觉事件被丢弃() {
        var stocks = List.of(stock(100L, 10L, "SKU-MILK", "2026-10-13", 5));
        var events = List.of(
                ev(1, 1, "VISION", "PICK", 10L, "SKU-MILK", null, "0.30")
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertFalse(r.uncertain());
        assertTrue(r.lines().isEmpty(), "低置信度事件被丢弃, 不产生拿取行");
    }

    @Test
    void 无重量时视觉RFID一致_确定计费() {
        var stocks = List.of(stock(100L, 10L, "SKU-MILK", "2026-10-13", 5));
        var events = List.of(
                ev(1, 1, "VISION", "PICK", 10L, "SKU-MILK", null, "0.90"),
                ev(2, 1, "RFID", "PICK", 10L, "SKU-MILK", null, null)
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertFalse(r.uncertain());
        assertEquals(1, r.lines().size());
        assertEquals(100L, r.lines().get(0).batchId());
    }

    @Test
    void 无重量仅视觉单模态_证据不足() {
        var stocks = List.of(stock(100L, 10L, "SKU-MILK", "2026-10-13", 5));
        var events = List.of(
                ev(1, 1, "VISION", "PICK", 10L, "SKU-MILK", null, "0.99")
        );
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertTrue(r.uncertain());
    }

    @Test
    void 重量锚定但货道多SKU且无识别_证据不足() {
        var stocks = List.of(
                stock(100L, 10L, "SKU-MILK", "2026-10-13", 5),
                stock(101L, 10L, "SKU-RICEBALL", "2026-10-08", 3)
        );
        var events = List.of(ev(1, 1, "WEIGHT", "PICK", 10L, null, -250, null));
        ReconcileResult r = reconciler.reconcile(events, stocks);
        assertTrue(r.uncertain());
        assertNull(r.lines().get(0).batchId());
    }
}
