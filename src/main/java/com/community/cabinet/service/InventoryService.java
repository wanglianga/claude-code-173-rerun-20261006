package com.community.cabinet.service;

import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 库存变更唯一入口: 每次变动同时更新 batch_inventory 聚合并写 inventory_ledger 流水,
 * 重算批次状态。补货/售卖/下架/温控/盘点全部走这里, 保证台账可回放。
 */
@Service
public class InventoryService {

    private final BatchInventoryRepository inventoryRepository;
    private final InventoryLedgerRepository ledgerRepository;
    private final ProductBatchRepository batchRepository;

    public InventoryService(BatchInventoryRepository inventoryRepository,
                            InventoryLedgerRepository ledgerRepository,
                            ProductBatchRepository batchRepository) {
        this.inventoryRepository = inventoryRepository;
        this.ledgerRepository = ledgerRepository;
        this.batchRepository = batchRepository;
    }

    /** 在架数增量(+上架/退回, -售出/下架/隔离), balance 记录变更后在架数 */
    @Transactional
    public BatchInventory changeOnShelf(Long batchId, Long channelId, Long cabinetId, String sku,
                                        int delta, String reason, String refType, Long refId,
                                        Long operatorId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        inv.setOnShelfQty(Math.max(0, inv.getOnShelfQty() + delta));
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, delta, inv.getOnShelfQty(),
                reason, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
        return inv;
    }

    /** 标记/取消临期数量(在架数量不变, 仅临期标记位变化) */
    @Transactional
    public void markNearExpiry(Long batchId, Long channelId, Long cabinetId, String sku,
                               int delta, String refType, Long refId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        inv.setNearExpiryQty(Math.max(0, inv.getNearExpiryQty() + delta));
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, 0, inv.getOnShelfQty(),
                C.L_NEAR_EXPIRY_MARK, refType, refId, null);
        recomputeBatchStatus(batchId);
    }

    /** 在架 -> 下架暂存 */
    @Transactional
    public void moveOnShelfToRemoved(Long batchId, Long channelId, Long cabinetId, String sku,
                                     int qty, String reason, String refType, Long refId, Long operatorId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        int q = Math.min(qty, inv.getOnShelfQty());
        if (q <= 0) throw new IllegalArgumentException("该货道该批次在架数不足");
        inv.setOnShelfQty(inv.getOnShelfQty() - q);
        inv.setNearExpiryQty(Math.max(0, inv.getNearExpiryQty() - Math.min(q, inv.getNearExpiryQty())));
        inv.setRemovedQty(inv.getRemovedQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, -q, inv.getOnShelfQty(),
                reason, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
    }

    /** 在架 -> 温控隔离 */
    @Transactional
    public void quarantine(Long batchId, Long channelId, Long cabinetId, String sku,
                           int qty, String refType, Long refId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        int q = Math.min(qty, inv.getOnShelfQty());
        inv.setOnShelfQty(inv.getOnShelfQty() - q);
        inv.setQuarantinedQty(inv.getQuarantinedQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, -q, inv.getOnShelfQty(),
                C.L_TEMP_QUARANTINE, refType, refId, null);
        recomputeBatchStatus(batchId);
    }

    /** 温控隔离 -> 解除(复核无问题回架) */
    @Transactional
    public void releaseQuarantine(Long batchId, Long channelId, Long cabinetId, String sku,
                                  int qty, String refType, Long refId, Long operatorId) {
        BatchInventory inv = require(batchId, channelId);
        int q = Math.min(qty, inv.getQuarantinedQty());
        if (q <= 0) throw new IllegalArgumentException("隔离数量不足");
        inv.setQuarantinedQty(inv.getQuarantinedQty() - q);
        inv.setOnShelfQty(inv.getOnShelfQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, q, inv.getOnShelfQty(),
                C.L_TEMP_RELEASE, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
    }

    /** 下架暂存 -> 报损 */
    @Transactional
    public void scrapFromRemoved(Long batchId, Long channelId, Long cabinetId, String sku,
                                 int qty, String refType, Long refId, Long operatorId) {
        BatchInventory inv = require(batchId, channelId);
        int q = Math.min(qty, inv.getRemovedQty());
        if (q <= 0) throw new IllegalArgumentException("下架暂存数量不足");
        inv.setRemovedQty(inv.getRemovedQty() - q);
        inv.setScrappedQty(inv.getScrappedQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, 0, inv.getOnShelfQty(),
                C.L_SCRAP, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
    }

    /** 隔离 -> 报损(温控变质) */
    @Transactional
    public void scrapFromQuarantine(Long batchId, Long channelId, Long cabinetId, String sku,
                                    int qty, String refType, Long refId, Long operatorId) {
        BatchInventory inv = require(batchId, channelId);
        int q = Math.min(qty, inv.getQuarantinedQty());
        if (q <= 0) throw new IllegalArgumentException("隔离数量不足");
        inv.setQuarantinedQty(inv.getQuarantinedQty() - q);
        inv.setScrappedQty(inv.getScrappedQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, 0, inv.getOnShelfQty(),
                C.L_SCRAP, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
    }

    /** 直接从在架报损(卡货损坏等) */
    @Transactional
    public void scrapFromOnShelf(Long batchId, Long channelId, Long cabinetId, String sku,
                                 int qty, String refType, Long refId, Long operatorId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        int q = Math.min(qty, inv.getOnShelfQty());
        if (q <= 0) throw new IllegalArgumentException("在架数量不足");
        inv.setOnShelfQty(inv.getOnShelfQty() - q);
        inv.setNearExpiryQty(Math.max(0, inv.getNearExpiryQty() - Math.min(q, inv.getNearExpiryQty())));
        inv.setScrappedQty(inv.getScrappedQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, -q, inv.getOnShelfQty(),
                C.L_SCRAP, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
    }

    /** 售卖: 在架 -> 已售 */
    @Transactional
    public void sale(Long batchId, Long channelId, Long cabinetId, String sku,
                     int qty, Long orderItemId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        int q = Math.min(qty, inv.getOnShelfQty());
        if (q <= 0) throw new IllegalArgumentException("库存不足无法售卖: batch=" + batchId);
        inv.setOnShelfQty(inv.getOnShelfQty() - q);
        inv.setNearExpiryQty(Math.max(0, inv.getNearExpiryQty() - Math.min(q, inv.getNearExpiryQty())));
        inv.setSoldQty(inv.getSoldQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, -q, inv.getOnShelfQty(),
                C.L_SALE, "ORDER_ITEM", orderItemId, null);
        recomputeBatchStatus(batchId);
    }

    /** 售卖冲销(误判/退款回架): 已售 -> 在架 */
    @Transactional
    public void reverseSale(Long batchId, Long channelId, Long cabinetId, String sku,
                            int qty, String reason, String refType, Long refId, Long operatorId) {
        BatchInventory inv = require(batchId, channelId);
        int q = Math.min(qty, inv.getSoldQty());
        if (q <= 0) throw new IllegalArgumentException("已售数量不足, 无法冲销");
        inv.setSoldQty(inv.getSoldQty() - q);
        inv.setOnShelfQty(inv.getOnShelfQty() + q);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, q, inv.getOnShelfQty(),
                reason, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
    }

    /**
     * 下架暂存, 优先消耗在架, 不足部分从温控隔离数量转出(隔离中的临期/过期商品同样必须下架)。
     * 返回实际处理数量。
     */
    @Transactional
    public int removeToRemovedFromAny(Long batchId, Long channelId, Long cabinetId, String sku,
                                      int qty, String refType, Long refId, Long operatorId) {
        BatchInventory inv = getOrCreate(batchId, channelId, cabinetId);
        int remain = qty;
        int fromShelf = Math.min(remain, inv.getOnShelfQty());
        if (fromShelf > 0) {
            inv.setOnShelfQty(inv.getOnShelfQty() - fromShelf);
            inv.setNearExpiryQty(Math.max(0, inv.getNearExpiryQty() - Math.min(fromShelf, inv.getNearExpiryQty())));
            remain -= fromShelf;
        }
        int fromQuar = Math.min(remain, inv.getQuarantinedQty());
        if (fromQuar > 0) {
            inv.setQuarantinedQty(inv.getQuarantinedQty() - fromQuar);
            remain -= fromQuar;
        }
        int handled = qty - remain;
        if (handled <= 0) throw new IllegalArgumentException("在架/隔离数量均不足, 无法下架");
        inv.setRemovedQty(inv.getRemovedQty() + handled);
        inv.setUpdatedAt(Ids.now());
        inventoryRepository.save(inv);
        writeLedger(batchId, channelId, cabinetId, sku, -handled, inv.getOnShelfQty(),
                C.L_REMOVE_EXPIRY, refType, refId, operatorId);
        recomputeBatchStatus(batchId);
        return handled;
    }

    /** 货道间移货(放错货道纠正): 两条流水 */
    @Transactional
    public void moveBetweenChannels(Long batchId, Long fromChannelId, Long toChannelId,
                                    Long cabinetId, String sku, int qty, Long refId, Long operatorId) {
        changeOnShelf(batchId, fromChannelId, cabinetId, sku, -qty,
                C.L_WRONG_CHANNEL_MOVE, "REPLENISH_ITEM", refId, operatorId);
        changeOnShelf(batchId, toChannelId, cabinetId, sku, qty,
                C.L_WRONG_CHANNEL_MOVE, "REPLENISH_ITEM", refId, operatorId);
    }

    private BatchInventory getOrCreate(Long batchId, Long channelId, Long cabinetId) {
        return inventoryRepository.findByBatchIdAndChannelId(batchId, channelId)
                .orElseGet(() -> {
                    BatchInventory inv = new BatchInventory();
                    inv.setBatchId(batchId);
                    inv.setChannelId(channelId);
                    inv.setCabinetId(cabinetId);
                    inv.setUpdatedAt(Ids.now());
                    return inventoryRepository.save(inv);
                });
    }

    private BatchInventory require(Long batchId, Long channelId) {
        return inventoryRepository.findByBatchIdAndChannelId(batchId, channelId)
                .orElseThrow(() -> new IllegalArgumentException("库存记录不存在"));
    }

    private void writeLedger(Long batchId, Long channelId, Long cabinetId, String sku,
                             int changeQty, int balanceQty, String reason,
                             String refType, Long refId, Long operatorId) {
        InventoryLedger l = new InventoryLedger();
        l.setBatchId(batchId);
        l.setChannelId(channelId);
        l.setCabinetId(cabinetId);
        l.setSku(sku);
        l.setChangeQty(changeQty);
        l.setBalanceQty(balanceQty);
        l.setReasonCode(reason);
        l.setRefType(refType);
        l.setRefId(refId);
        l.setOperatorId(operatorId);
        l.setCreatedAt(Ids.now());
        ledgerRepository.save(l);
    }

    /** 依据该批次在所有柜机的库存聚合重算状态(SETTLED 终态不动) */
    @Transactional
    public void recomputeBatchStatus(Long batchId) {
        ProductBatch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || C.BATCH_SETTLED.equals(batch.getStatus())) {
            return;
        }
        var rows = inventoryRepository.findByBatchId(batchId);
        if (rows.isEmpty()) {
            if (!C.BATCH_REMOVED.equals(batch.getStatus())) {
                batch.setStatus(C.BATCH_IN_WAREHOUSE);
                batchRepository.save(batch);
            }
            return;
        }
        int onShelf = rows.stream().mapToInt(BatchInventory::getOnShelfQty).sum();
        int quar = rows.stream().mapToInt(BatchInventory::getQuarantinedQty).sum();
        int removed = rows.stream().mapToInt(BatchInventory::getRemovedQty).sum();
        int near = rows.stream().mapToInt(BatchInventory::getNearExpiryQty).sum();
        int sold = rows.stream().mapToInt(BatchInventory::getSoldQty).sum();
        int scrapped = rows.stream().mapToInt(BatchInventory::getScrappedQty).sum();

        String status;
        if (quar > 0) {
            status = C.BATCH_QUARANTINED;
        } else if (near > 0) {
            status = C.BATCH_NEAR_EXPIRY;
        } else if (onShelf > 0) {
            status = C.BATCH_ON_SHELF;
        } else if (scrapped > 0 && removed == 0 && sold == 0) {
            status = C.BATCH_SCRAPPED;
        } else if (removed > 0) {
            status = C.BATCH_REMOVED;
        } else {
            status = C.BATCH_IN_WAREHOUSE;
        }
        if (!status.equals(batch.getStatus())) {
            batch.setStatus(status);
            batchRepository.save(batch);
        }
    }
}
