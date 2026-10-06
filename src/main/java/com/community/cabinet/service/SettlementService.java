package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 供应商结算: 按账期汇总每个供应商批次的真实售卖(成本口径)与报损。
 * 应付款 = 售卖成本 - 报损成本(过期/温控变质损耗按合同由供应商承担),
 * 结算确认后报损记录置 settled, 批次进入 SETTLED。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SupplierRepository supplierRepository;
    private final ProductBatchRepository batchRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final ScrapRecordRepository scrapRepository;
    private final EventPublisher publisher;

    public SettlementService(SettlementRepository settlementRepository,
                             SettlementItemRepository settlementItemRepository,
                             SupplierRepository supplierRepository,
                             ProductBatchRepository batchRepository,
                             BatchInventoryRepository inventoryRepository,
                             ScrapRecordRepository scrapRepository,
                             EventPublisher publisher) {
        this.settlementRepository = settlementRepository;
        this.settlementItemRepository = settlementItemRepository;
        this.supplierRepository = supplierRepository;
        this.batchRepository = batchRepository;
        this.inventoryRepository = inventoryRepository;
        this.scrapRepository = scrapRepository;
        this.publisher = publisher;
    }

    /** 生成(预览)某供应商账期结算单草稿; 重复账期拒绝 */
    @Transactional
    public Settlement generate(Long supplierId, LocalDate periodStart, LocalDate periodEnd, Long staffId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在"));
        if (settlementRepository.existsBySupplierIdAndPeriodStartAndPeriodEnd(supplierId, periodStart, periodEnd)) {
            throw ApiException.conflict("该供应商此账期结算单已存在");
        }

        Settlement head = new Settlement();
        head.setSettlementNo(Ids.gen("STL"));
        head.setSupplierId(supplierId);
        head.setPeriodStart(periodStart);
        head.setPeriodEnd(periodEnd);
        head.setStatus("DRAFT");
        head.setStaffId(staffId);
        head.setCreatedAt(Ids.now());
        head = settlementRepository.save(head);

        BigDecimal saleTotal = BigDecimal.ZERO;
        BigDecimal scrapTotal = BigDecimal.ZERO;

        // 账期内未结算的报损(按批次聚合)
        Map<Long, int[]> scrapByBatch = new HashMap<>();
        for (ScrapRecord scrap : scrapRepository.findBySupplierIdAndSettled(supplierId, false)) {
            if (scrap.getCreatedAt().toLocalDate().isBefore(periodStart)
                    || scrap.getCreatedAt().toLocalDate().isAfter(periodEnd)) continue;
            scrapByBatch.computeIfAbsent(scrap.getBatchId(), k -> new int[1])[0] += scrap.getQty();
        }

        Set<Long> batchIds = new HashSet<>(scrapByBatch.keySet());
        for (ProductBatch batch : batchRepository.findBySupplierId(supplierId)) {
            List<BatchInventory> rows = inventoryRepository.findByBatchId(batch.getId());
            int sold = rows.stream().mapToInt(BatchInventory::getSoldQty).sum();
            int scrapped = scrapByBatch.getOrDefault(batch.getId(), new int[1])[0];
            if (sold <= 0 && scrapped <= 0) continue;
            batchIds.add(batch.getId());

            BigDecimal saleCost = batch.getUnitCost().multiply(BigDecimal.valueOf(sold));
            BigDecimal scrapCost = batch.getUnitCost().multiply(BigDecimal.valueOf(scrapped));
            BigDecimal payable = saleCost.subtract(scrapCost);

            SettlementItem si = new SettlementItem();
            si.setSettlementId(head.getId());
            si.setBatchId(batch.getId());
            si.setSoldQty(sold);
            si.setSaleCostAmount(saleCost);
            si.setScrappedQty(scrapped);
            si.setScrapCostAmount(scrapCost);
            si.setPayable(payable);
            settlementItemRepository.save(si);
            saleTotal = saleTotal.add(saleCost);
            scrapTotal = scrapTotal.add(scrapCost);
        }

        head.setSaleAmount(saleTotal);
        head.setScrapAmount(scrapTotal);
        head.setPayableAmount(saleTotal.subtract(scrapTotal));
        settlementRepository.save(head);
        publisher.publish("SETTLEMENT_GENERATED", head.getSettlementNo(), Map.of(
                "settlementNo", head.getSettlementNo(), "supplierId", supplierId,
                "sale", saleTotal.toPlainString(), "scrap", scrapTotal.toPlainString(),
                "payable", head.getPayableAmount().toPlainString()));
        return head;
    }

    /** 确认结算: 报损置 settled, 批次置 SETTLED */
    @Transactional
    public Settlement confirm(Long settlementId, Long staffId, String note) {
        Settlement head = settlementRepository.findById(settlementId)
                .orElseThrow(() -> ApiException.notFound("结算单不存在"));
        if (!"DRAFT".equals(head.getStatus())) throw ApiException.conflict("结算单非草稿状态");

        for (SettlementItem si : settlementItemRepository.findBySettlementId(settlementId)) {
            ProductBatch batch = batchRepository.findById(si.getBatchId()).orElseThrow();
            // 关联本批次账期内报损记录
            for (ScrapRecord scrap : scrapRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId())) {
                if (!scrap.getSettled()
                        && !scrap.getCreatedAt().toLocalDate().isBefore(head.getPeriodStart())
                        && !scrap.getCreatedAt().toLocalDate().isAfter(head.getPeriodEnd())) {
                    scrap.setSettled(true);
                    scrapRepository.save(scrap);
                }
            }
            if (si.getScrappedQty() > 0 || si.getSoldQty() > 0) {
                batch.setStatus(C.BATCH_SETTLED);
                batchRepository.save(batch);
            }
        }
        head.setStatus("CONFIRMED");
        head.setConfirmedAt(Ids.now());
        head.setStaffId(staffId);
        head.setNote(note);
        settlementRepository.save(head);
        publisher.publish("SETTLEMENT_CONFIRMED", head.getSettlementNo(), Map.of(
                "settlementNo", head.getSettlementNo(), "payable", head.getPayableAmount().toPlainString()));
        return head;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Settlement head = settlementRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("结算单不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlement", head);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SettlementItem si : settlementItemRepository.findBySettlementId(id)) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("item", si);
            batchRepository.findById(si.getBatchId())
                    .ifPresent(b -> im.put("batchNo", b.getBatchNo()));
            items.add(im);
        }
        m.put("items", items);
        return m;
    }

    @Transactional(readOnly = true)
    public List<Settlement> bySupplier(Long supplierId) {
        return settlementRepository.findBySupplierIdOrderByPeriodEndDesc(supplierId);
    }
}
