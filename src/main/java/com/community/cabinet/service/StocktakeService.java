package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 盘点: 以柜机为单位拉取系统库存 -> 补货员录入实盘数 -> 差异归因
 * (卡货/识别偏差/放错货道/原因不明/初盘错误) -> 确认调整, 写 STOCKTAKE_ADJUST 流水。
 * 盘亏产生 STOCK_MISMATCH 告警。
 */
@Service
public class StocktakeService {

    private final StocktakeRepository stocktakeRepository;
    private final StocktakeItemRepository itemRepository;
    private final CabinetRepository cabinetRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final ProductBatchRepository batchRepository;
    private final ChannelRepository channelRepository;
    private final InventoryService inventoryService;
    private final AlertService alertService;
    private final EventPublisher publisher;

    public StocktakeService(StocktakeRepository stocktakeRepository, StocktakeItemRepository itemRepository,
                            CabinetRepository cabinetRepository, BatchInventoryRepository inventoryRepository,
                            ProductBatchRepository batchRepository, ChannelRepository channelRepository,
                            InventoryService inventoryService, AlertService alertService,
                            EventPublisher publisher) {
        this.stocktakeRepository = stocktakeRepository;
        this.itemRepository = itemRepository;
        this.cabinetRepository = cabinetRepository;
        this.inventoryRepository = inventoryRepository;
        this.batchRepository = batchRepository;
        this.channelRepository = channelRepository;
        this.inventoryService = inventoryService;
        this.alertService = alertService;
        this.publisher = publisher;
    }

    @Transactional
    public Stocktake create(String cabinetCode, Long staffId, String note) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        Stocktake st = new Stocktake();
        st.setStocktakeNo(Ids.gen("ST"));
        st.setCabinetId(cabinet.getId());
        st.setStaffId(staffId);
        st.setStartedAt(Ids.now());
        st.setStatus("OPEN");
        st.setNote(note);
        st = stocktakeRepository.save(st);

        // 生成系统快照明细(在架>0 的批次货道)
        for (BatchInventory inv : inventoryRepository.findByCabinetId(cabinet.getId())) {
            if (inv.getOnShelfQty() <= 0) continue;
            StocktakeItem item = new StocktakeItem();
            item.setStocktakeId(st.getId());
            item.setBatchId(inv.getBatchId());
            item.setChannelId(inv.getChannelId());
            item.setSystemQty(inv.getOnShelfQty());
            item.setCountedQty(inv.getOnShelfQty()); // 默认账实一致, 由实盘覆盖
            item.setDiffQty(0);
            item.setReason("OK");
            item.setHandled(false);
            itemRepository.save(item);
        }
        return st;
    }

    /** 录入实盘数量与差异原因 */
    @Transactional
    public StocktakeItem count(Long itemId, int countedQty, String reason, String note) {
        StocktakeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("盘点明细不存在"));
        Stocktake st = stocktakeRepository.findById(item.getStocktakeId()).orElseThrow();
        if (!"OPEN".equals(st.getStatus())) throw ApiException.conflict("盘点任务已完成");
        item.setCountedQty(countedQty);
        item.setDiffQty(countedQty - item.getSystemQty());
        item.setReason(item.getDiffQty() == 0 ? "OK" : (reason == null ? "UNKNOWN_LOSS" : reason));
        item.setNote(note);
        return itemRepository.save(item);
    }

    /** 确认盘点: 差异调整库存 */
    @Transactional
    public Stocktake confirm(Long stocktakeId) {
        Stocktake st = stocktakeRepository.findById(stocktakeId)
                .orElseThrow(() -> ApiException.notFound("盘点任务不存在"));
        if (!"OPEN".equals(st.getStatus())) throw ApiException.conflict("盘点任务已完成");
        List<StocktakeItem> items = itemRepository.findByStocktakeId(st.getId());

        int mismatchCount = 0;
        for (StocktakeItem item : items) {
            if (item.getDiffQty() == 0 || Boolean.TRUE.equals(item.getHandled())) continue;
            ProductBatch batch = batchRepository.findById(item.getBatchId()).orElseThrow();
            int diff = item.getDiffQty();
            if (diff < 0) {
                // 盘亏: 在架调减
                inventoryService.changeOnShelf(batch.getId(), item.getChannelId(), st.getCabinetId(),
                        batch.getSku(), diff, C.L_STOCKTAKE_ADJUST, "STOCKTAKE_ITEM", item.getId(),
                        st.getStaffId());
            } else {
                // 盘盈: 在架调增(常见于放回未识别/初盘错误)
                inventoryService.changeOnShelf(batch.getId(), item.getChannelId(), st.getCabinetId(),
                        batch.getSku(), diff, C.L_STOCKTAKE_ADJUST, "STOCKTAKE_ITEM", item.getId(),
                        st.getStaffId());
            }
            item.setHandled(true);
            itemRepository.save(item);
            mismatchCount++;
        }
        st.setStatus(mismatchCount > 0 ? "ADJUSTED" : "CONFIRMED");
        st.setCompletedAt(Ids.now());
        stocktakeRepository.save(st);
        if (mismatchCount > 0) {
            alertService.warn(st.getCabinetId(), C.ALERT_MISMATCH, "STOCKTAKE", st.getId(),
                    "盘点 " + st.getStocktakeNo() + " 发现 " + mismatchCount + " 项账实不符, 已调整");
        }
        publisher.publish("STOCKTAKE_CONFIRMED", st.getStocktakeNo(), Map.of(
                "stocktakeNo", st.getStocktakeNo(), "mismatchItems", mismatchCount,
                "status", st.getStatus()));
        return st;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Stocktake st = stocktakeRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("盘点任务不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stocktake", st);
        List<Map<String, Object>> items = new ArrayList<>();
        for (StocktakeItem item : itemRepository.findByStocktakeId(id)) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("item", item);
            batchRepository.findById(item.getBatchId())
                    .ifPresent(b -> im.put("batchNo", b.getBatchNo()));
            channelRepository.findById(item.getChannelId())
                    .ifPresent(c -> im.put("channelCode", c.getCode()));
            items.add(im);
        }
        m.put("items", items);
        return m;
    }
}
