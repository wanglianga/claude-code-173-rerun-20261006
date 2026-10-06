package com.community.cabinet.bootstrap;

import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 种子数据: 测试账号 + 供应商/商品/批次 + 三类点位柜机货道。
 * 幂等(以唯一编码判断是否已存在)。
 */
@Component
@Profile("!test")
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final StaffRepository staffRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final ProductBatchRepository batchRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;

    public SeedDataLoader(StaffRepository staffRepository, SupplierRepository supplierRepository,
                          ProductRepository productRepository, ProductBatchRepository batchRepository,
                          CabinetRepository cabinetRepository, ChannelRepository channelRepository) {
        this.staffRepository = staffRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (staffRepository.count() > 0) return;

        Supplier fresh = supplier("鲜食达冷链", "张经理 13800000001", "ACCT-FRESH-001");
        Supplier drink = supplier("悦饮供应链", "李经理 13800000002", "ACCT-DRINK-002");

        staff("root", "超级管理员", C.ROLE_ROOT, "root-token", null);
        staff("operator", "运营小王", C.ROLE_OPERATOR, "operator-token", null);
        staff("finance", "财务小陈", C.ROLE_FINANCE, "finance-token", null);
        staff("cs", "客服小周", C.ROLE_CS, "cs-token", null);
        staff("replenisher", "补货员老刘", C.ROLE_REPLENISHER, "replenisher-token", null);
        staff("supplier", "鲜食达供应商账号", C.ROLE_SUPPLIER, "supplier-token", fresh.getId());

        Product milk = product("SKU-MILK", "低温鲜牛奶 250ml", "乳品",
                "6.50", "3.00", C.TEMP_CHILLED);
        Product rice = product("SKU-RICEBALL", "金枪鱼饭团", "鲜食",
                "8.00", "4.00", C.TEMP_CHILLED);
        Product water = product("SKU-WATER", "矿泉水 550ml", "饮品",
                "2.00", "0.80", C.TEMP_AMBIENT);
        Product dumpling = product("SKU-DUMPLING", "速冻水饺", "速冻",
                "15.00", "8.00", C.TEMP_FROZEN);

        LocalDate today = LocalDate.now();
        batch("B-MILK-001", milk, fresh, today.minusDays(5), today.plusDays(7));
        // 临期批次(2 天后到期)
        batch("B-RICE-NEAR", rice, fresh, today.minusDays(2), today.plusDays(2));
        // 已过期批次(在仓内即过期, 用于演示补货拦截与临期治理)
        batch("B-RICE-EXPIRED", rice, fresh, today.minusDays(10), today.minusDays(1));
        batch("B-WATER-001", water, drink, today.minusDays(20), today.plusDays(180));
        batch("B-DUMP-001", dumpling, fresh, today.minusDays(30), today.plusDays(150));

        Cabinet community = cabinet("CB-SQ-001", "阳光花园社区南门柜", "COMMUNITY", "阳光花园小区南门大堂", "MIXED");
        Cabinet office = cabinet("CB-OF-001", "环球中心A座12层柜", "OFFICE", "环球中心A座12层茶水间", "CHILLED");
        Cabinet hospital = cabinet("CB-HP-001", "市中心医院住院部柜", "HOSPITAL", "住院部一楼大厅", "MIXED");

        channel(community, "A1", "SKU-MILK", C.TEMP_CHILLED, 12);
        channel(community, "A2", "SKU-RICEBALL", C.TEMP_CHILLED, 10);
        channel(community, "B1", "SKU-WATER", C.TEMP_AMBIENT, 20);
        channel(office, "A1", "SKU-MILK", C.TEMP_CHILLED, 12);
        channel(office, "A2", "SKU-RICEBALL", C.TEMP_CHILLED, 10);
        channel(hospital, "A1", "SKU-RICEBALL", C.TEMP_CHILLED, 10);
        channel(hospital, "B1", "SKU-WATER", C.TEMP_AMBIENT, 20);

        log.info("种子数据初始化完成: 6 个测试账号, 2 家供应商, 4 个商品, 5 个批次, 3 台柜机");
    }

    private Supplier supplier(String name, String contact, String account) {
        Supplier s = new Supplier();
        s.setName(name); s.setContact(contact); s.setSettlementAccount(account);
        s.setCreatedAt(Ids.now());
        return supplierRepository.save(s);
    }

    private void staff(String name, String display, String role, String token, Long supplierId) {
        Staff s = new Staff();
        s.setName(display); s.setRole(role); s.setToken(token);
        s.setSupplierId(supplierId); s.setActive(true); s.setCreatedAt(Ids.now());
        staffRepository.save(s);
    }

    private Product product(String sku, String name, String category,
                            String price, String cost, String temp) {
        Product p = new Product();
        p.setSku(sku); p.setName(name); p.setCategory(category);
        p.setUnitPrice(new BigDecimal(price)); p.setCostPrice(new BigDecimal(cost));
        p.setTempRequirement(temp); p.setActive(true); p.setCreatedAt(Ids.now());
        return productRepository.save(p);
    }

    private void batch(String no, Product p, Supplier s, LocalDate prod, LocalDate expiry) {
        if (batchRepository.findByBatchNo(no).isPresent()) return;
        ProductBatch b = new ProductBatch();
        b.setBatchNo(no); b.setSku(p.getSku()); b.setSupplierId(s.getId());
        b.setProductionDate(prod); b.setExpiryDate(expiry);
        b.setTempRequirement(p.getTempRequirement()); b.setInboundQty(100);
        b.setUnitCost(p.getCostPrice()); b.setStatus(C.BATCH_IN_WAREHOUSE);
        b.setCreatedAt(Ids.now());
        batchRepository.save(b);
    }

    private Cabinet cabinet(String code, String name, String locType, String address, String tempType) {
        return cabinetRepository.findByCode(code).orElseGet(() -> {
            Cabinet c = new Cabinet();
            c.setCode(code); c.setName(name); c.setLocationType(locType);
            c.setAddress(address); c.setTempType(tempType); c.setStatus("NORMAL");
            c.setActive(true); c.setCreatedAt(Ids.now());
            return cabinetRepository.save(c);
        });
    }

    private void channel(Cabinet c, String code, String expectedSku, String tempType, int capacity) {
        if (channelRepository.findByCabinetIdAndCode(c.getId(), code).isPresent()) return;
        Channel ch = new Channel();
        ch.setCabinetId(c.getId()); ch.setCode(code); ch.setExpectedSku(expectedSku);
        ch.setTempType(tempType); ch.setCapacity(capacity); ch.setActive(true);
        channelRepository.save(ch);
    }
}
