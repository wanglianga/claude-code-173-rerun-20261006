package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 批次全生命周期追溯: 补货 -> 售卖 -> 下架 -> 报损 -> 结算。
 * 输出批次信息、在柜分布、库存流水时间线、订单、下架、报损。供应商与运营按批次查看损耗原因。
 */
@Service
public class BatchTraceService {

    private final ProductBatchRepository batchRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final InventoryLedgerRepository ledgerRepository;
    private final OrderItemRepository orderItemRepository;
    private final SaleOrderRepository orderRepository;
    private final RemovalRecordRepository removalRepository;
    private final ScrapRecordRepository scrapRepository;
    private final ReplenishmentItemRepository replenishmentItemRepository;

    public BatchTraceService(ProductBatchRepository batchRepository,
                             BatchInventoryRepository inventoryRepository,
                             InventoryLedgerRepository ledgerRepository,
                             OrderItemRepository orderItemRepository,
                             SaleOrderRepository orderRepository,
                             RemovalRecordRepository removalRepository,
                             ScrapRecordRepository scrapRepository,
                             ReplenishmentItemRepository replenishmentItemRepository) {
        this.batchRepository = batchRepository;
        this.inventoryRepository = inventoryRepository;
        this.ledgerRepository = ledgerRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.removalRepository = removalRepository;
        this.scrapRepository = scrapRepository;
        this.replenishmentItemRepository = replenishmentItemRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> trace(String batchNo) {
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchNo));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batch", batch);
        view.put("inventoryByChannel", inventoryRepository.findByBatchId(batch.getId()));
        view.put("ledger", ledgerRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId()));

        List<Map<String, Object>> orderLines = new ArrayList<>();
        for (OrderItem oi : orderItemRepository.findByBatchId(batch.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderItem", oi);
            orderRepository.findById(oi.getOrderId()).ifPresent(o -> {
                m.put("orderNo", o.getOrderNo());
                m.put("userId", o.getUserId());
                m.put("createdAt", o.getCreatedAt());
                m.put("orderStatus", o.getStatus());
            });
            orderLines.add(m);
        }
        view.put("sales", orderLines);
        view.put("removals", removalRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId()));
        view.put("scraps", scrapRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId()));

        // 汇总
        int replenished = 0;
        for (var inv : inventoryRepository.findByBatchId(batch.getId())) {
            replenished += inv.getSoldQty() + inv.getOnShelfQty() + inv.getQuarantinedQty()
                    + inv.getRemovedQty() + inv.getScrappedQty() + inv.getNearExpiryQty() * 0;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("onShelf", inventoryRepository.findByBatchId(batch.getId()).stream()
                .mapToInt(BatchInventory::getOnShelfQty).sum());
        summary.put("sold", inventoryRepository.findByBatchId(batch.getId()).stream()
                .mapToInt(BatchInventory::getSoldQty).sum());
        summary.put("quarantined", inventoryRepository.findByBatchId(batch.getId()).stream()
                .mapToInt(BatchInventory::getQuarantinedQty).sum());
        summary.put("removed", inventoryRepository.findByBatchId(batch.getId()).stream()
                .mapToInt(BatchInventory::getRemovedQty).sum());
        summary.put("scrapped", inventoryRepository.findByBatchId(batch.getId()).stream()
                .mapToInt(BatchInventory::getScrappedQty).sum());
        view.put("summary", summary);
        return view;
    }
}
