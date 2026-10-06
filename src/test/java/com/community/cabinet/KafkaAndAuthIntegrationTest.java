package com.community.cabinet;

import com.community.cabinet.common.C;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.Topics;
import com.community.cabinet.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Kafka 主题端到端 + HTTP 令牌/角色鉴权 */
class KafkaAndAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private CabinetRepository cabinetRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductBatchRepository batchRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private BatchInventoryRepository inventoryRepository;
    @Autowired private DoorSessionRepository sessionRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private InventoryService inventoryService;
    @Autowired private TestRestTemplate rest;

    private static final String CODE = "K-CB-01";

    private void seedCabinetOnce() {
        if (cabinetRepository.findByCode(CODE).isPresent()) return;
        Supplier sup = new Supplier();
        sup.setName("K供应商"); sup.setCreatedAt(java.time.LocalDateTime.now());
        sup = supplierRepository.save(sup);
        Product p = new Product();
        p.setSku("K-MILK"); p.setName("牛奶"); p.setCategory("d");
        p.setUnitPrice(new BigDecimal("6.00")); p.setCostPrice(new BigDecimal("3.00"));
        p.setTempRequirement(C.TEMP_CHILLED); p.setActive(true); p.setCreatedAt(java.time.LocalDateTime.now());
        p = productRepository.save(p);
        ProductBatch b = new ProductBatch();
        b.setBatchNo("KB-1"); b.setSku("K-MILK"); b.setSupplierId(sup.getId());
        b.setProductionDate(LocalDate.now().minusDays(1)); b.setExpiryDate(LocalDate.now().plusDays(7));
        b.setTempRequirement(C.TEMP_CHILLED); b.setInboundQty(50); b.setUnitCost(new BigDecimal("3.00"));
        b.setStatus(C.BATCH_IN_WAREHOUSE); b.setCreatedAt(java.time.LocalDateTime.now());
        b = batchRepository.save(b);
        Cabinet c = new Cabinet();
        c.setCode(CODE); c.setName("K柜"); c.setLocationType("COMMUNITY");
        c.setTempType("MIXED"); c.setStatus("NORMAL"); c.setActive(true);
        c.setCreatedAt(java.time.LocalDateTime.now());
        c = cabinetRepository.save(c);
        Channel ch = new Channel();
        ch.setCabinetId(c.getId()); ch.setCode("A1"); ch.setExpectedSku("K-MILK");
        ch.setTempType(C.TEMP_CHILLED); ch.setCapacity(20); ch.setActive(true);
        ch = channelRepository.save(ch);
        inventoryService.changeOnShelf(b.getId(), ch.getId(), c.getId(), "K-MILK", 5,
                C.L_REPLENISH, "SEED", null, null);
    }

    @Test
    void 设备事件经Kafka主题消费_开门识别关门全链路() throws Exception {
        seedCabinetOnce();
        Cabinet c = cabinetRepository.findByCode(CODE).orElseThrow();
        long before = sessionRepository.count();

        kafkaTemplate.send(Topics.DEVICE_EVENTS, "k1", """
            {"eventId":"evt-k-1","eventType":"DOOR_OPEN","cabinetCode":"K-CB-01",
             "data":{"userId":"U-K-1","videoClipUrl":"http://v/1"}}
            """).get();

        // 等待开门落库并取会话号
        DoorSession session = waitFor(() ->
                sessionRepository.findByUserIdOrderByOpenedAtDesc("U-K-1").stream().findFirst().orElse(null));
        assertNotNull(session);

        String sn = session.getSessionNo();
        kafkaTemplate.send(Topics.DEVICE_EVENTS, "k2", """
            {"eventId":"evt-k-2","eventType":"RECOGNITION","cabinetCode":"K-CB-01",
             "data":{"sessionNo":"%s","seq":1,"modality":"WEIGHT","action":"PICK",
                      "channelCode":"A1","weightDeltaG":-600}}
            """.formatted(sn)).get();
        kafkaTemplate.send(Topics.DEVICE_EVENTS, "k3", """
            {"eventId":"evt-k-3","eventType":"RECOGNITION","cabinetCode":"K-CB-01",
             "data":{"sessionNo":"%s","seq":2,"modality":"VISION","action":"PICK",
                      "channelCode":"A1","sku":"K-MILK","confidence":0.96}}
            """.formatted(sn)).get();
        kafkaTemplate.send(Topics.DEVICE_EVENTS, "k4", """
            {"eventId":"evt-k-4","eventType":"RECOGNITION","cabinetCode":"K-CB-01",
             "data":{"sessionNo":"%s","seq":3,"modality":"RFID","action":"PICK",
                      "channelCode":"A1","sku":"K-MILK"}}
            """.formatted(sn)).get();
        kafkaTemplate.send(Topics.DEVICE_EVENTS, "k5", """
            {"eventId":"evt-k-5","eventType":"DOOR_CLOSE","cabinetCode":"K-CB-01",
             "data":{"sessionNo":"%s","clipEndMs":9000}}
            """.formatted(sn)).get();

        // 重复事件幂等
        kafkaTemplate.send(Topics.DEVICE_EVENTS, "k1dup", """
            {"eventId":"evt-k-1","eventType":"DOOR_OPEN","cabinetCode":"K-CB-01",
             "data":{"userId":"U-K-1"}}
            """).get();

        DoorSession settled = waitFor(() -> {
            DoorSession s = sessionRepository.findBySessionNo(sn).orElseThrow();
            return C.SETTLE_SETTLED.equals(s.getSettleStatus()) ? s : null;
        });
        assertNotNull(settled.getOrderId());
        assertEquals(before + 1, sessionRepository.count(), "重复开门事件必须幂等");
        var inv = inventoryRepository.findByCabinetId(c.getId()).stream()
                .filter(i -> i.getOnShelfQty() >= 0).findFirst().orElseThrow();
        // 初始 5 件, 售出 1 件
        assertEquals(4, inventoryRepository.findByChannelId(inv.getChannelId()).stream()
                .filter(i -> "K-MILK".equals(batchRepository.findById(i.getBatchId())
                        .map(ProductBatch::getSku).orElse(null)))
                .findFirst().orElseThrow().getOnShelfQty());
    }

    @Test
    void http鉴权_无令牌401_越权403_正确令牌200() {
        HttpHeaders noToken = new HttpHeaders();
        ResponseEntity<String> r1 = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(noToken), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, r1.getStatusCode());

        // 补货员访问运营接口 -> 403
        ensureStaff("op-http", C.ROLE_OPERATOR, "op-http-token", null);
        ensureStaff("rp-http", C.ROLE_REPLENISHER, "rp-http-token", null);
        HttpHeaders rp = new HttpHeaders();
        rp.set("X-Staff-Token", "rp-http-token");
        ResponseEntity<String> r2 = rest.exchange("/api/operations/metrics?cabinetCode=" + CODE,
                HttpMethod.GET, new HttpEntity<>(rp), String.class);
        assertEquals(HttpStatus.FORBIDDEN, r2.getStatusCode());

        HttpHeaders op = new HttpHeaders();
        op.set("X-Staff-Token", "op-http-token");
        ResponseEntity<String> r3 = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(op), String.class);
        assertEquals(HttpStatus.OK, r3.getStatusCode());
        assertTrue(r3.getBody().contains("OPERATOR"));
    }

    private void ensureStaff(String name, String role, String token, Long supplierId) {
        if (staffRepository.findByToken(token).isPresent()) return;
        Staff s = new Staff();
        s.setName(name); s.setRole(role); s.setToken(token);
        s.setSupplierId(supplierId); s.setActive(true); s.setCreatedAt(java.time.LocalDateTime.now());
        staffRepository.save(s);
    }

    private <T> T waitFor(java.util.function.Supplier<T> cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        T v;
        do {
            v = cond.get();
            if (v != null) return v;
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        fail("等待条件超时");
        return null;
    }
}
