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
import java.util.List;
import java.util.Map;

/**
 * 报损: 过期/温控变质/卡货损坏等。统一产生 scrap_record(成本金额, 关联供应商与批次),
 * 供财务按真实报损结算。库存联动由 InventoryService 完成。
 */
@Service
public class ScrapService {

    private final ScrapRecordRepository scrapRepository;
    private final ProductBatchRepository batchRepository;
    private final InventoryService inventoryService;
    private final EventPublisher publisher;

    public ScrapService(ScrapRecordRepository scrapRepository,
                        ProductBatchRepository batchRepository,
                        InventoryService inventoryService,
                        EventPublisher publisher) {
        this.scrapRepository = scrapRepository;
        this.batchRepository = batchRepository;
        this.inventoryService = inventoryService;
        this.publisher = publisher;
    }

    /** 从在架直接报损(卡货损坏等) */
    @Transactional
    public ScrapRecord scrapOnShelf(Long batchId, Long cabinetId, Long channelId,
                                    String reason, Long staffId, String note, int qty) {
        ProductBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在"));
        inventoryService.scrapFromOnShelf(batchId, channelId, cabinetId, batch.getSku(),
                qty, "SCRAP", null, staffId);
        return createRecord(batch, cabinetId, qty, reason, staffId, note);
    }

    /** 从下架暂存报损(临期/过期下架后) */
    @Transactional
    public ScrapRecord scrapRemoved(Long batchId, Long cabinetId, Long channelId,
                                    String reason, Long staffId, String note, int qty) {
        ProductBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在"));
        inventoryService.scrapFromRemoved(batchId, channelId, cabinetId, batch.getSku(),
                qty, "SCRAP", null, staffId);
        return createRecord(batch, cabinetId, qty, reason, staffId, note);
    }

    /** 从温控隔离报损(温控变质) */
    @Transactional
    public ScrapRecord scrapQuarantined(Long batchId, Long cabinetId, Long channelId,
                                        String reason, Long staffId, String note, int qty) {
        ProductBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在"));
        inventoryService.scrapFromQuarantine(batchId, channelId, cabinetId, batch.getSku(),
                qty, "SCRAP", null, staffId);
        return createRecord(batch, cabinetId, qty, reason, staffId, note);
    }

    private ScrapRecord createRecord(ProductBatch batch, Long cabinetId, int qty,
                                     String reason, Long staffId, String note) {
        ScrapRecord rec = new ScrapRecord();
        rec.setScrapNo(Ids.gen("SC"));
        rec.setBatchId(batch.getId());
        rec.setCabinetId(cabinetId);
        rec.setSupplierId(batch.getSupplierId());
        rec.setQty(qty);
        rec.setCostAmount(batch.getUnitCost().multiply(BigDecimal.valueOf(qty)));
        rec.setReason(reason);
        rec.setSettled(false);
        rec.setStaffId(staffId);
        rec.setCreatedAt(Ids.now());
        rec.setNote(note);
        rec = scrapRepository.save(rec);
        inventoryService.recomputeBatchStatus(batch.getId());
        publisher.publish("SCRAP_RECORDED", rec.getScrapNo(), Map.of(
                "scrapNo", rec.getScrapNo(), "batchNo", batch.getBatchNo(),
                "qty", qty, "cost", rec.getCostAmount().toPlainString(), "reason", reason));
        return rec;
    }

    @Transactional(readOnly = true)
    public List<ScrapRecord> byBatch(Long batchId) {
        return scrapRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
    }
}
