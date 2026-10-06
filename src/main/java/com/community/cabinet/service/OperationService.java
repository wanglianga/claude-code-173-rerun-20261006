package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 运营端: 按点位统计销量/损耗/临期率, 支持按点位调整货道品类。
 * 指标用于安排补货: 低销量高损耗高临期率的 SKU 建议汰换, 反之增加排面。
 */
@Service
public class OperationService {

    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ProductRepository productRepository;
    private final SaleOrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ScrapRecordRepository scrapRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final ProductBatchRepository batchRepository;
    private final RemovalRecordRepository removalRepository;
    private final ReplenishmentRepository replenishmentRepository;
    private final CategoryAdjustmentRepository adjustmentRepository;
    private final AlertService alertService;

    public OperationService(CabinetRepository cabinetRepository, ChannelRepository channelRepository,
                            ProductRepository productRepository, SaleOrderRepository orderRepository,
                            OrderItemRepository orderItemRepository, ScrapRecordRepository scrapRepository,
                            BatchInventoryRepository inventoryRepository, ProductBatchRepository batchRepository,
                            RemovalRecordRepository removalRepository,
                            ReplenishmentRepository replenishmentRepository,
                            CategoryAdjustmentRepository adjustmentRepository,
                            AlertService alertService) {
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.scrapRepository = scrapRepository;
        this.inventoryRepository = inventoryRepository;
        this.batchRepository = batchRepository;
        this.removalRepository = removalRepository;
        this.replenishmentRepository = replenishmentRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.alertService = alertService;
    }

    /** 点位经营指标(指定天数窗口) */
    @Transactional(readOnly = true)
    public Map<String, Object> pointMetrics(String cabinetCode, int days) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        LocalDateTime from = LocalDate.now().minusDays(days).atStartOfDay();
        LocalDateTime to = Ids.now();

        List<SaleOrder> orders = orderRepository.findByCabinetIdAndCreatedAtBetween(cabinet.getId(), from, to);
        BigDecimal salesAmount = BigDecimal.ZERO;
        Map<String, int[]> skuSold = new HashMap<>(); // sku -> [qty]
        for (SaleOrder o : orders) {
            if (List.of(C.ORDER_CANCELLED).contains(o.getStatus())) continue;
            salesAmount = salesAmount.add(o.getTotalAmount());
            for (OrderItem oi : orderItemRepository.findByOrderId(o.getId())) {
                if (C.ITEM_CHARGED.equals(oi.getStatus()) || C.ITEM_REFUNDED.equals(oi.getStatus())) {
                    skuSold.merge(oi.getSku(), new int[]{oi.getQty()}, (a, b) -> {a[0]+=b[0]; return a;});
                }
            }
        }

        List<ScrapRecord> scraps = scrapRepository.findByCabinetIdAndCreatedAtBetween(cabinet.getId(), from, to);
        BigDecimal scrapCost = scraps.stream().map(ScrapRecord::getCostAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Integer> scrapBySku = new HashMap<>();
        for (ScrapRecord s : scraps) {
            batchRepository.findById(s.getBatchId())
                    .ifPresent(b -> scrapBySku.merge(b.getSku(), s.getQty(), Integer::sum));
        }

        List<RemovalRecord> removals = removalRepository.findByCabinetIdAndCreatedAtBetween(cabinet.getId(), from, to);
        long nearCount = removals.stream().filter(r -> "NEAR_EXPIRY".equals(r.getReason())).count();
        long expiredCount = removals.stream().filter(r -> "EXPIRED".equals(r.getReason())).count();

        // 当前库存与低库存货道
        List<Map<String, Object>> channelStock = new ArrayList<>();
        int lowStockChannels = 0;
        for (Channel ch : channelRepository.findByCabinetId(cabinet.getId())) {
            int onShelf = inventoryRepository.findByChannelId(ch.getId()).stream()
                    .mapToInt(BatchInventory::getOnShelfQty).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channelCode", ch.getCode());
            m.put("expectedSku", ch.getExpectedSku());
            m.put("tempType", ch.getTempType());
            m.put("onShelfQty", onShelf);
            m.put("capacity", ch.getCapacity());
            m.put("lowStock", onShelf <= Math.max(1, ch.getCapacity() / 5));
            if (onShelf <= Math.max(1, ch.getCapacity() / 5)) lowStockChannels++;
            channelStock.add(m);
        }

        // 按 SKU 汇总销量/损耗/临期, 给出品类建议
        List<Map<String, Object>> skuStats = new ArrayList<>();
        Set<String> allSkus = new HashSet<>(skuSold.keySet());
        allSkus.addAll(scrapBySku.keySet());
        for (String sku : allSkus) {
            int sold = skuSold.getOrDefault(sku, new int[1])[0];
            int scrapped = scrapBySku.getOrDefault(sku, 0);
            double lossRate = (sold + scrapped) == 0 ? 0 : (double) scrapped / (sold + scrapped);
            String name = productRepository.findBySku(sku).map(Product::getName).orElse(sku);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", sku);
            m.put("name", name);
            m.put("soldQty", sold);
            m.put("scrappedQty", scrapped);
            m.put("lossRate", Math.round(lossRate * 10000) / 100.0);
            m.put("suggestion", suggestion(sold, lossRate));
            skuStats.add(m);
        }
        skuStats.sort((a, b) -> Integer.compare((Integer) b.get("soldQty"), (Integer) a.get("soldQty")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cabinetCode", cabinet.getCode());
        result.put("cabinetName", cabinet.getName());
        result.put("windowDays", days);
        result.put("orderCount", orders.size());
        result.put("salesAmount", salesAmount);
        result.put("scrapCost", scrapCost);
        result.put("nearExpiryCount", nearCount);
        result.put("expiredCount", expiredCount);
        result.put("lowStockChannels", lowStockChannels);
        result.put("channelStock", channelStock);
        result.put("skuStats", skuStats);
        return result;
    }

    private String suggestion(int sold, double lossRate) {
        if (sold == 0 && lossRate > 0) return "建议汰换: 无销量且有损耗";
        if (lossRate >= 0.3) return "建议减量/汰换: 损耗率过高";
        if (sold >= 20 && lossRate < 0.05) return "建议增加排面: 高销量低损耗";
        return "维持";
    }

    /** 调整货道品类: 创建调整单(待执行, 下一次补货生效); applyNow=true 立即更新 expectedSku */
    @Transactional
    public CategoryAdjustment adjustCategory(String cabinetCode, String channelCode,
                                             String newExpectedSku, String reason,
                                             boolean applyNow, Long staffId) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        Channel channel = channelRepository.findByCabinetIdAndCode(cabinet.getId(), channelCode)
                .orElseThrow(() -> ApiException.notFound("货道不存在"));
        if (newExpectedSku != null && productRepository.findBySku(newExpectedSku).isEmpty()) {
            throw ApiException.badRequest("SKU 不存在: " + newExpectedSku);
        }
        CategoryAdjustment adj = new CategoryAdjustment();
        adj.setCabinetId(cabinet.getId());
        adj.setChannelId(channel.getId());
        adj.setOldExpectedSku(channel.getExpectedSku());
        adj.setNewExpectedSku(newExpectedSku);
        adj.setReason(reason);
        adj.setStaffId(staffId);
        adj.setCreatedAt(Ids.now());
        if (applyNow) {
            channel.setExpectedSku(newExpectedSku);
            channelRepository.save(channel);
            adj.setApplied(true);
            adj.setAppliedAt(Ids.now());
        }
        adj = adjustmentRepository.save(adj);
        return adj;
    }

    @Transactional(readOnly = true)
    public List<CategoryAdjustment> adjustments(String cabinetCode) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        return adjustmentRepository.findByCabinetIdOrderByCreatedAtDesc(cabinet.getId());
    }

    /** 低库存扫描, 生成补货提醒 */
    @Transactional
    public int scanLowStock() {
        int n = 0;
        for (Cabinet cabinet : cabinetRepository.findAll()) {
            for (Channel ch : channelRepository.findByCabinetId(cabinet.getId())) {
                int onShelf = inventoryRepository.findByChannelId(ch.getId()).stream()
                        .mapToInt(BatchInventory::getOnShelfQty).sum();
                if (onShelf <= Math.max(1, ch.getCapacity() / 5)) {
                    if (alertService.info(cabinet.getId(), C.ALERT_LOW_STOCK, "CHANNEL", ch.getId(),
                            "货道 " + ch.getCode() + " 库存 " + onShelf + "/" + ch.getCapacity() + " 需补货") != null) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
