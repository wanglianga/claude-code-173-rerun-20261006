package com.community.cabinet;

import com.community.cabinet.common.C;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全链路: 补货(放错货道) -> 三模售卖 -> 拿起放回 -> 证据不足人工复核
 * -> 温控异常隔离报损 -> 临期/过期下架报损 -> 盘点差异调整 -> 投诉退款回库
 * -> 供应商结算 -> 批次追溯/运营指标。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullScenarioIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductBatchRepository batchRepository;
    @Autowired private CabinetRepository cabinetRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private BatchInventoryRepository inventoryRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private SaleOrderRepository orderRepository;
    @Autowired private ReplenishmentItemRepository replenishmentItemRepository;
    @Autowired private TemperatureAlarmRepository alarmRepository;
    @Autowired private ScrapRecordRepository scrapRepository;
    @Autowired private RemovalRecordRepository removalRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private StaffRepository staffRepository;

    @Autowired private ReplenishmentService replenishmentService;
    @Autowired private InventoryService inventoryService;
    @Autowired private SessionService sessionService;
    @Autowired private TemperatureService temperatureService;
    @Autowired private ScrapService scrapService;
    @Autowired private ExpiryService expiryService;
    @Autowired private StocktakeService stocktakeService;
    @Autowired private ComplaintService complaintService;
    @Autowired private SettlementService settlementService;
    @Autowired private BatchTraceService traceService;
    @Autowired private OperationService operationService;

    private Supplier supplier;
    private Product milk, rice;
    private ProductBatch milkBatch, nearBatch, expiredBatch;
    private Cabinet cabinet;
    private Channel milkChannel, riceChannel;
    private Staff staff;

    @BeforeEach
    void setup() {
        // 数据在同一 Spring 上下文的测试方法间共享, 每个方法实例都需重新装载字段
        staff = staffRepository.findByToken("test-staff-token").orElseGet(() -> {
            Staff s = new Staff();
            s.setName("测试补货员"); s.setRole(C.ROLE_REPLENISHER);
            s.setToken("test-staff-token"); s.setActive(true);
            s.setCreatedAt(java.time.LocalDateTime.now());
            return staffRepository.save(s);
        });
        if (cabinetRepository.findByCode("T-CB-01").isPresent()) {
            loadExisting();
            return;
        }
        supplier = new Supplier();
        supplier.setName("测试供应商"); supplier.setContact("c"); supplier.setCreatedAt(java.time.LocalDateTime.now());
        supplier = supplierRepository.save(supplier);

        milk = newProduct("T-MILK", "鲜奶", C.TEMP_CHILLED, "6.50", "3.00");
        rice = newProduct("T-RICE", "饭团", C.TEMP_CHILLED, "8.00", "4.00");

        LocalDate today = LocalDate.now();
        milkBatch = newBatch("TB-MILK", milk, today.plusDays(10));
        nearBatch = newBatch("TB-NEAR", rice, today.plusDays(2));
        expiredBatch = newBatch("TB-EXPIRED", rice, today.minusDays(1));

        cabinet = new Cabinet();
        cabinet.setCode("T-CB-01"); cabinet.setName("测试柜");
        cabinet.setLocationType("COMMUNITY"); cabinet.setTempType("MIXED");
        cabinet.setStatus("NORMAL"); cabinet.setActive(true); cabinet.setCreatedAt(java.time.LocalDateTime.now());
        cabinet = cabinetRepository.save(cabinet);

        milkChannel = newChannel("A1", "T-MILK", C.TEMP_CHILLED);
        riceChannel = newChannel("A2", "T-RICE", C.TEMP_CHILLED);
    }

    private void loadExisting() {
        cabinet = cabinetRepository.findByCode("T-CB-01").orElseThrow();
        supplier = supplierRepository.findAll().get(0);
        milk = productRepository.findBySku("T-MILK").orElseThrow();
        rice = productRepository.findBySku("T-RICE").orElseThrow();
        milkBatch = batchRepository.findByBatchNo("TB-MILK").orElseThrow();
        nearBatch = batchRepository.findByBatchNo("TB-NEAR").orElseThrow();
        expiredBatch = batchRepository.findByBatchNo("TB-EXPIRED").orElseThrow();
        milkChannel = channelRepository.findByCabinetIdAndCode(cabinet.getId(), "A1").orElseThrow();
        riceChannel = channelRepository.findByCabinetIdAndCode(cabinet.getId(), "A2").orElseThrow();
    }

    private Product newProduct(String sku, String name, String temp, String price, String cost) {
        Product p = new Product();
        p.setSku(sku); p.setName(name); p.setCategory("c");
        p.setUnitPrice(new BigDecimal(price)); p.setCostPrice(new BigDecimal(cost));
        p.setTempRequirement(temp); p.setActive(true); p.setCreatedAt(java.time.LocalDateTime.now());
        return productRepository.save(p);
    }

    private ProductBatch newBatch(String no, Product p, LocalDate expiry) {
        ProductBatch b = new ProductBatch();
        b.setBatchNo(no); b.setSku(p.getSku()); b.setSupplierId(supplier.getId());
        b.setProductionDate(expiry.minusDays(5)); b.setExpiryDate(expiry);
        b.setTempRequirement(p.getTempRequirement()); b.setInboundQty(100);
        b.setUnitCost(p.getCostPrice()); b.setStatus(C.BATCH_IN_WAREHOUSE);
        b.setCreatedAt(java.time.LocalDateTime.now());
        return batchRepository.save(b);
    }

    private Channel newChannel(String code, String sku, String temp) {
        Channel ch = new Channel();
        ch.setCabinetId(cabinet.getId()); ch.setCode(code); ch.setExpectedSku(sku);
        ch.setTempType(temp); ch.setCapacity(20); ch.setActive(true);
        return channelRepository.save(ch);
    }

    private int onShelf(Long batchId, Long channelId) {
        return inventoryRepository.findByBatchIdAndChannelId(batchId, channelId)
                .map(BatchInventory::getOnShelfQty).orElse(0);
    }

    @Test
    @Order(1)
    void 补货_放错货道被拦截_纠正后上架() {
        Replenishment r = replenishmentService.arrive("T-CB-01", staff.getId(), "http://photos/1.jpg", "到货");
        // 鲜奶放 A1 正常
        var ok = replenishmentService.addItem(r.getId(), "TB-MILK", "A1", 10);
        assertEquals("OK", ok.getStatus());
        // 饭团放到牛奶货道 -> 放错货道
        var wrong = replenishmentService.addItem(r.getId(), "TB-NEAR", "A1", 5);
        assertEquals("WRONG_CHANNEL", wrong.getStatus());
        // 确认应被阻断
        assertThrows(Exception.class, () -> replenishmentService.confirm(r.getId(), false));
        // 纠正到 A2
        replenishmentService.correctItem(wrong.getId(), "A2");
        assertEquals("OK", replenishmentItemRepository.findById(wrong.getId()).orElseThrow().getStatus());
        // 过期批次禁止上架
        assertThrows(Exception.class, () -> replenishmentService.addItem(r.getId(), "TB-EXPIRED", "A2", 2));
        replenishmentService.confirm(r.getId(), false);

        assertEquals(10, onShelf(milkBatch.getId(), milkChannel.getId()));
        assertEquals(5, onShelf(nearBatch.getId(), riceChannel.getId()));
    }

    @Test
    @Order(2)
    void 三模一致售卖_扣库存() {
        // 再补 2 件牛奶
        Replenishment r2 = replenishmentService.arrive("T-CB-01", staff.getId(), null, null);
        replenishmentService.addItem(r2.getId(), "TB-MILK", "A1", 2);
        replenishmentService.confirm(r2.getId(), false);

        DoorSession s = sessionService.openSession("T-CB-01", "U-1001", "http://video/1", 0L);
        var t = java.time.LocalDateTime.now().minusMinutes(1);
        sessionService.ingestEvent(s.getId(), "WEIGHT", "PICK", "A1", null, null, -650, null, null, 1);
        sessionService.ingestEvent(s.getId(), "VISION", "PICK", "A1", "T-MILK", null, null,
                new BigDecimal("0.95"), null, 2);
        sessionService.ingestEvent(s.getId(), "RFID", "PICK", "A1", "T-MILK", null, null, null, null, 3);
        SaleOrder order = sessionService.closeSession(s.getSessionNo(), 1000L);

        assertEquals(C.ORDER_CONFIRMED, order.getStatus());
        assertEquals(0, new BigDecimal("6.50").compareTo(order.getTotalAmount()));
        // 11 件牛奶售出 1 件
        assertEquals(11, onShelf(milkBatch.getId(), milkChannel.getId()));
        // 证据回放含开门/识别/视频/库存变化
        Map<String, Object> replay = sessionService.evidenceReplay(s.getSessionNo());
        assertFalse(((List<?>) replay.get("events")).isEmpty());
        assertFalse(((List<?>) replay.get("evidences")).isEmpty());
        assertFalse(((List<?>) replay.get("inventoryChanges")).isEmpty());
    }

    @Test
    @Order(3)
    void 拿起又放回不计费() {
        DoorSession s = sessionService.openSession("T-CB-01", "U-1002", null, null);
        sessionService.ingestEvent(s.getId(), "WEIGHT", "PICK", "A1", null, null, -650, null, null, 1);
        sessionService.ingestEvent(s.getId(), "VISION", "PICK", "A1", "T-MILK", null, null,
                new BigDecimal("0.9"), null, 2);
        sessionService.ingestEvent(s.getId(), "VISION", "RETURN", "A1", "T-MILK", null, null,
                new BigDecimal("0.9"), null, 3);
        sessionService.ingestEvent(s.getId(), "WEIGHT", "RETURN", "A1", null, null, 648, null, null, 4);
        SaleOrder order = sessionService.closeSession(s.getSessionNo(), null);
        assertEquals(C.ORDER_CONFIRMED, order.getStatus());
        assertEquals(0, order.getTotalAmount().signum());
    }

    @Test
    @Order(4)
    void 证据不足订单人工复核后扣款() {
        DoorSession s = sessionService.openSession("T-CB-01", "U-1003", null, null);
        // 仅视觉, 无重量/RFID
        sessionService.ingestEvent(s.getId(), "VISION", "PICK", "A1", "T-MILK", null, null,
                new BigDecimal("0.99"), null, 1);
        SaleOrder order = sessionService.closeSession(s.getSessionNo(), null);
        assertEquals(C.ORDER_UNCERTAIN, order.getStatus());
        int before = onShelf(milkBatch.getId(), milkChannel.getId());

        SaleOrder reviewed = sessionService.reviewOrder(order.getId(), true, "视频确认拿走", 99L);
        assertEquals(C.ORDER_CONFIRMED, reviewed.getStatus());
        assertEquals(before - 1, onShelf(milkBatch.getId(), milkChannel.getId()));
    }

    @Test
    @Order(5)
    void 温度异常隔离并报损() {
        // 12°C 对 MIXED(冷藏阈值) 为异常
        var log = temperatureService.record("T-CB-01", new BigDecimal("12.0"), "S1");
        assertEquals("HIGH", log.getStatus());
        TemperatureAlarm alarm = alarmRepository
                .findFirstByCabinetIdAndStatusOrderByStartedAtDesc(cabinet.getId(), "ACTIVE").orElseThrow();
        // 恢复采样
        temperatureService.record("T-CB-01", new BigDecimal("5.0"), "S1");
        // 隔离全柜在架
        temperatureService.quarantineCabinet(alarm.getId());
        // 隔离后牛奶批次应有隔离数量, 对其中 2 件报损(温控变质)
        var inv = inventoryRepository.findByBatchIdAndChannelId(milkBatch.getId(), milkChannel.getId()).orElseThrow();
        int quar = inv.getQuarantinedQty();
        assertTrue(quar > 0);
        scrapService.scrapQuarantined(milkBatch.getId(), cabinet.getId(), milkChannel.getId(),
                "TEMP_SPOIL", null, "温度超标变质", 2);
        // 其余解除隔离回架
        temperatureService.release(alarm.getId(), milkBatch.getId(), milkChannel.getId(),
                quar - 2, null, "复核合格");
        // A2 饭团同样复核合格回架
        var riceInv = inventoryRepository.findByBatchIdAndChannelId(nearBatch.getId(), riceChannel.getId()).orElseThrow();
        if (riceInv.getQuarantinedQty() > 0) {
            temperatureService.release(alarm.getId(), nearBatch.getId(), riceChannel.getId(),
                    riceInv.getQuarantinedQty(), null, "复核合格");
        }
        assertEquals(0, inventoryRepository.findByBatchIdAndChannelId(milkBatch.getId(), milkChannel.getId())
                .orElseThrow().getQuarantinedQty());
    }

    @Test
    @Order(6)
    void 临期扫描生成下架任务并报损() {
        // 过期批次模拟"在柜内到期": 直接预置 3 件在架(补货接口禁止上架已过期商品)
        inventoryService.changeOnShelf(expiredBatch.getId(), riceChannel.getId(), cabinet.getId(),
                "T-RICE", 3, C.L_REPLENISH, "SEED", null, null);
        // 再补货 4 件临期饭团
        Replenishment r = replenishmentService.arrive("T-CB-01", staff.getId(), null, null);
        replenishmentService.addItem(r.getId(), "TB-NEAR", "A2", 4);
        replenishmentService.confirm(r.getId(), true);
        int created = expiryService.scan();
        assertTrue(created >= 2, "应为过期+临期生成下架任务, 实际 " + created);

        List<RemovalRecord> pendings = removalRepository.findByStatus("PENDING");
        assertFalse(pendings.isEmpty());
        // 全部处置为报损; 过期任务不允许回架
        for (RemovalRecord rec : pendings) {
            if ("EXPIRED".equals(rec.getReason())) {
                assertThrows(Exception.class,
                        () -> expiryService.handle(rec.getId(), "RETURNED", null, "试图回架"));
            }
            expiryService.handle(rec.getId(), "SCRAPPED", null, "过期销毁");
        }
        assertTrue(scrapRepository.findAll().stream().anyMatch(s -> "EXPIRED".equals(s.getReason())));
    }

    @Test
    @Order(7)
    void 盘点账实不符调整并告警() {
        Stocktake st = stocktakeService.create("T-CB-01", staff.getId(), "月盘");
        var items = stocktakeService.detail(st.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) items.get("items");
        assertFalse(rows.isEmpty());
        StocktakeItem first = (StocktakeItem) rows.get(0).get("item");
        stocktakeService.count(first.getId(), first.getSystemQty() - 1, "UNKNOWN_LOSS", "盘亏1件");
        Stocktake done = stocktakeService.confirm(st.getId());
        assertEquals("ADJUSTED", done.getStatus());
    }

    @Test
    @Order(8)
    void 投诉误扣款退款并回库() {
        // 构造一笔确定售卖
        DoorSession s = sessionService.openSession("T-CB-01", "U-1009", null, null);
        sessionService.ingestEvent(s.getId(), "WEIGHT", "PICK", "A1", null, null, -650, null, null, 1);
        sessionService.ingestEvent(s.getId(), "VISION", "PICK", "A1", "T-MILK", null, null,
                new BigDecimal("0.95"), null, 2);
        sessionService.ingestEvent(s.getId(), "RFID", "PICK", "A1", "T-MILK", null, null, null, null, 3);
        SaleOrder order = sessionService.closeSession(s.getSessionNo(), null);
        int soldBefore = onShelf(milkBatch.getId(), milkChannel.getId());
        OrderItem oi = orderItemRepository.findByOrderId(order.getId()).get(0);

        Complaint c = complaintService.create("U-1009", s.getSessionNo(), "没拿牛奶被扣款");
        Refund refund = complaintService.refund(c.getId(), order.getId(),
                order.getTotalAmount(), "MISCHARGE", "识别错误全额退", List.of(oi.getId()), staff.getId());
        assertEquals("DONE", refund.getStatus());
        assertEquals(C.ORDER_REFUNDED, orderRepository.findById(order.getId()).orElseThrow().getStatus());
        // 回库
        assertEquals(soldBefore + 1, onShelf(milkBatch.getId(), milkChannel.getId()));
        assertFalse(refundRepository.findByOrderId(order.getId()).isEmpty());
    }

    @Test
    @Order(9)
    void 供应商结算按真实售卖与报损() {
        LocalDate today = LocalDate.now();
        Settlement stl = settlementService.generate(supplier.getId(),
                today.minusDays(30), today.plusDays(1), null);
        assertTrue(stl.getSaleAmount().signum() > 0);
        assertTrue(stl.getScrapAmount().signum() > 0, "存在温控+过期报损");
        assertEquals(0, stl.getPayableAmount().compareTo(
                stl.getSaleAmount().subtract(stl.getScrapAmount())));
        Settlement confirmed = settlementService.confirm(stl.getId(), staff.getId(), "账期确认");
        assertEquals("CONFIRMED", confirmed.getStatus());
        Map<String, Object> detail = settlementService.detail(stl.getId());
        assertFalse(((List<?>) detail.get("items")).isEmpty());
    }

    @Test
    @Order(10)
    void 批次追溯与运营指标() {
        Map<String, Object> trace = traceService.trace("TB-MILK");
        assertNotNull(trace.get("batch"));
        assertFalse(((List<?>) trace.get("ledger")).isEmpty());
        assertFalse(((List<?>) trace.get("scraps")).isEmpty());

        Map<String, Object> metrics = operationService.pointMetrics("T-CB-01", 30);
        assertNotNull(metrics.get("skuStats"));
        assertNotNull(metrics.get("channelStock"));
        operationService.adjustCategory("T-CB-01", "A2", "T-MILK", "饭团损耗高改卖牛奶", true, staff.getId());
        assertEquals("T-MILK", channelRepository.findById(riceChannel.getId()).orElseThrow().getExpectedSku());
    }
}
