package com.community.cabinet.service;

import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 临期/过期治理:
 * - 定时扫描在柜批次: <= 临期阈值天数 -> 临期标记 + 下架任务(NEAR_EXPIRY) + 告警;
 *   已过保质期 -> 下架任务(EXPIRED, CRITICAL), 自动生成待下架任务, 必须下架不得售卖;
 * - 处置: REMOVED 下架暂存(可退仓) / RETURNED 复核合格回架 / SCRAPPED 报损。
 */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    private final ProductBatchRepository batchRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final RemovalRecordRepository removalRepository;
    private final InventoryService inventoryService;
    private final ScrapService scrapService;
    private final AlertService alertService;
    private final EventPublisher publisher;

    @Value("${app.expiry.near-expiry-days:2}")
    private int nearExpiryDays;

    public ExpiryService(ProductBatchRepository batchRepository,
                         BatchInventoryRepository inventoryRepository,
                         RemovalRecordRepository removalRepository,
                         InventoryService inventoryService,
                         ScrapService scrapService,
                         AlertService alertService,
                         EventPublisher publisher) {
        this.batchRepository = batchRepository;
        this.inventoryRepository = inventoryRepository;
        this.removalRepository = removalRepository;
        this.inventoryService = inventoryService;
        this.scrapService = scrapService;
        this.alertService = alertService;
        this.publisher = publisher;
    }

    /** 定时扫描(默认每 10 分钟); 返回新建下架任务数 */
    @Scheduled(cron = "${app.expiry.scan-cron:0 */10 * * * *}")
    @Transactional
    public int scheduledScan() {
        try {
            return scan();
        } catch (Exception e) {
            log.error("临期扫描失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Transactional
    public int scan() {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(nearExpiryDays);
        // 在柜相关状态的批次
        List<ProductBatch> candidates = batchRepository
                .findByExpiryDateLessThanEqualAndStatusIn(threshold,
                        List.of(C.BATCH_ON_SHELF, C.BATCH_NEAR_EXPIRY, C.BATCH_QUARANTINED));
        int created = 0;
        for (ProductBatch batch : candidates) {
            // 在架与温控隔离中的商品都必须纳入临期/过期治理
            List<BatchInventory> rows = inventoryRepository.findByBatchId(batch.getId()).stream()
                    .filter(i -> i.getOnShelfQty() + i.getQuarantinedQty() > 0).toList();
            boolean expired = batch.getExpiryDate().isBefore(today);
            for (BatchInventory inv : rows) {
                int available = inv.getOnShelfQty() + inv.getQuarantinedQty();
                int pending = removalRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId()).stream()
                        .filter(r -> r.getChannelId().equals(inv.getChannelId())
                                && ("PENDING".equals(r.getStatus()) || "REMOVED".equals(r.getStatus())))
                        .mapToInt(RemovalRecord::getQty).sum();
                int need = available - pending;
                if (need <= 0) continue;
                RemovalRecord rec = new RemovalRecord();
                rec.setRemovalNo(Ids.gen("RM"));
                rec.setCabinetId(inv.getCabinetId());
                rec.setBatchId(batch.getId());
                rec.setChannelId(inv.getChannelId());
                rec.setQty(need);
                rec.setReason(expired ? "EXPIRED" : "NEAR_EXPIRY");
                rec.setStatus("PENDING");
                rec.setCreatedAt(Ids.now());
                removalRepository.save(rec);
                created++;

                if (expired) {
                    alertService.critical(inv.getCabinetId(), C.ALERT_EXPIRED, "REMOVAL", rec.getId(),
                            "批次 " + batch.getBatchNo() + " 已于 " + batch.getExpiryDate()
                                    + " 过期, " + need + " 件必须立即下架");
                } else {
                    if (inv.getOnShelfQty() > 0) {
                        int toMark = Math.min(need,
                                Math.max(0, inv.getOnShelfQty() - inv.getNearExpiryQty()));
                        if (toMark > 0) {
                            inventoryService.markNearExpiry(batch.getId(), inv.getChannelId(), inv.getCabinetId(),
                                    batch.getSku(), toMark, "REMOVAL", rec.getId());
                        }
                    }
                    alertService.warn(inv.getCabinetId(), C.ALERT_NEAR, "REMOVAL", rec.getId(),
                            "批次 " + batch.getBatchNo() + " 将于 " + batch.getExpiryDate()
                                    + " 到期, " + need + " 件临期待下架");
                }
                publisher.publish(expired ? "EXPIRED_FOUND" : "NEAR_EXPIRY_FOUND",
                        batch.getBatchNo(), Map.of(
                                "batchNo", batch.getBatchNo(), "qty", need,
                                "expiryDate", batch.getExpiryDate().toString(),
                                "removalNo", rec.getRemovalNo()));
            }
        }
        return created;
    }

    /** 处置下架任务 */
    @Transactional
    public RemovalRecord handle(Long removalId, String action, Long staffId, String note) {
        RemovalRecord rec = removalRepository.findById(removalId)
                .orElseThrow(() -> new IllegalArgumentException("下架任务不存在"));
        if (!"PENDING".equals(rec.getStatus())) {
            throw new IllegalStateException("下架任务已处置: " + rec.getStatus());
        }
        ProductBatch batch = batchRepository.findById(rec.getBatchId()).orElseThrow();
        switch (action) {
            case "REMOVED" -> {
                inventoryService.removeToRemovedFromAny(batch.getId(), rec.getChannelId(), rec.getCabinetId(),
                        batch.getSku(), rec.getQty(), "REMOVAL", rec.getId(), staffId);
                rec.setStatus("REMOVED");
            }
            case "RETURNED" -> {
                // 复核合格回架(仅限临期且仍未过期)
                if ("EXPIRED".equals(rec.getReason())) {
                    throw new IllegalArgumentException("过期商品不允许复核回架");
                }
                inventoryService.markNearExpiry(batch.getId(), rec.getChannelId(), rec.getCabinetId(),
                        batch.getSku(), -Math.min(rec.getQty(),
                                inventoryRepository.findByBatchIdAndChannelId(batch.getId(), rec.getChannelId())
                                        .map(BatchInventory::getNearExpiryQty).orElse(0)),
                        "REMOVAL", rec.getId());
                rec.setStatus("RETURNED");
            }
            case "SCRAPPED" -> {
                String reason = "EXPIRED".equals(rec.getReason()) || "NEAR_EXPIRY".equals(rec.getReason())
                        ? "EXPIRED" : rec.getReason();
                // 先下架(在架或隔离)再报损
                inventoryService.removeToRemovedFromAny(batch.getId(), rec.getChannelId(), rec.getCabinetId(),
                        batch.getSku(), rec.getQty(), "REMOVAL", rec.getId(), staffId);
                scrapService.scrapRemoved(batch.getId(), rec.getCabinetId(), rec.getChannelId(),
                        reason, staffId, "下架任务报损: " + note, rec.getQty());
                rec.setStatus("SCRAPPED");
            }
            default -> throw new IllegalArgumentException("未知处置动作: " + action);
        }
        rec.setHandledAt(Ids.now());
        rec.setStaffId(staffId);
        rec.setNote(note);
        removalRepository.save(rec);
        publisher.publish("REMOVAL_HANDLED", rec.getRemovalNo(), Map.of(
                "removalNo", rec.getRemovalNo(), "action", action));
        return rec;
    }

    @Transactional(readOnly = true)
    public List<RemovalRecord> pending() {
        return removalRepository.findByStatus("PENDING");
    }
}
