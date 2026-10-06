package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 补货: 到货登记 -> 逐批次/货道录入(校验温度要求与货道商品一致性)
 * -> 确认上架(写库存与流水)。放错货道不阻断录入, 标记 WRONG_CHANNEL 并告警,
 * 由补货员纠正移货或确认差异。
 */
@Service
public class ReplenishmentService {

    private final ReplenishmentRepository replenishmentRepository;
    private final ReplenishmentItemRepository itemRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ProductBatchRepository batchRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final AlertService alertService;
    private final EventPublisher publisher;

    public ReplenishmentService(ReplenishmentRepository replenishmentRepository,
                                ReplenishmentItemRepository itemRepository,
                                CabinetRepository cabinetRepository,
                                ChannelRepository channelRepository,
                                ProductBatchRepository batchRepository,
                                BatchInventoryRepository inventoryRepository,
                                InventoryService inventoryService,
                                AlertService alertService,
                                EventPublisher publisher) {
        this.replenishmentRepository = replenishmentRepository;
        this.itemRepository = itemRepository;
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
        this.batchRepository = batchRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryService = inventoryService;
        this.alertService = alertService;
        this.publisher = publisher;
    }

    @Transactional
    public Replenishment arrive(String cabinetCode, Long staffId, String photoUrl, String note) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetCode));
        Replenishment r = new Replenishment();
        r.setOrderNo(Ids.gen("RP"));
        r.setCabinetId(cabinet.getId());
        r.setStaffId(staffId);
        r.setArrivedAt(Ids.now());
        r.setStatus("OPEN");
        r.setPhotoUrl(photoUrl);
        r.setNote(note);
        r.setCreatedAt(Ids.now());
        r = replenishmentRepository.save(r);
        publisher.publish("REPLENISH_ARRIVED", r.getOrderNo(), Map.of(
                "orderNo", r.getOrderNo(), "cabinetCode", cabinetCode, "photoUrl", String.valueOf(photoUrl)));
        return r;
    }

    /** 录入一行补货(暂不上架), 返回含问题标记的明细 */
    @Transactional
    public ReplenishmentItem addItem(Long replenishmentId, String batchNo, String channelCode, int qty) {
        if (qty <= 0) throw ApiException.badRequest("补货数量必须大于 0");
        Replenishment r = replenishmentRepository.findById(replenishmentId)
                .orElseThrow(() -> ApiException.notFound("补货单不存在"));
        if (!"OPEN".equals(r.getStatus())) throw ApiException.conflict("补货单已确认, 不能再录入");
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchNo));
        if (batch.getExpiryDate().isBefore(LocalDate.now())) {
            throw ApiException.badRequest("批次 " + batchNo + " 已过期, 禁止上架");
        }
        Channel channel = channelRepository.findByCabinetIdAndCode(r.getCabinetId(), channelCode)
                .orElseThrow(() -> ApiException.notFound("货道不存在: " + channelCode));

        ReplenishmentItem item = new ReplenishmentItem();
        item.setReplenishmentId(r.getId());
        item.setBatchId(batch.getId());
        item.setChannelId(channel.getId());
        item.setSku(batch.getSku());
        item.setQty(qty);
        item.setExpiryDate(batch.getExpiryDate());
        item.setCreatedAt(Ids.now());

        List<String> issues = new ArrayList<>();
        // 温度要求校验: 商品温层必须与货道温层兼容
        if (!channel.getTempType().equals(batch.getTempRequirement())
                && !C.TEMP_AMBIENT.equals(channel.getTempType())) {
            issues.add("温度要求不符: 商品要求" + batch.getTempRequirement() + ", 货道为" + channel.getTempType());
        }
        // 货道商品一致性: expectedSku 不匹配视为放错货道
        if (channel.getExpectedSku() != null && !channel.getExpectedSku().equals(batch.getSku())) {
            issues.add("放错货道: 货道期望SKU=" + channel.getExpectedSku() + ", 实际=" + batch.getSku());
        }
        if (!issues.isEmpty()) {
            item.setStatus("WRONG_CHANNEL");
            item.setIssueNote(String.join("; ", issues));
            alertService.critical(r.getCabinetId(), C.ALERT_WRONG_CHANNEL, "REPLENISH_ITEM", null,
                    "补货单 " + r.getOrderNo() + " 货道 " + channelCode + ": " + item.getIssueNote());
        }
        return itemRepository.save(item);
    }

    /** 确认上架: 正常明细入库存; WRONG_CHANNEL 明细必须先纠正或显式接受(forceIssue=true) */
    @Transactional
    public Replenishment confirm(Long replenishmentId, boolean forceIssue) {
        Replenishment r = replenishmentRepository.findById(replenishmentId)
                .orElseThrow(() -> ApiException.notFound("补货单不存在"));
        if (!"OPEN".equals(r.getStatus())) throw ApiException.conflict("补货单已确认");
        List<ReplenishmentItem> items = itemRepository.findByReplenishmentId(r.getId());
        if (items.isEmpty()) throw ApiException.badRequest("补货单无明细, 不能确认");

        boolean hasIssue = items.stream().anyMatch(i -> "WRONG_CHANNEL".equals(i.getStatus()));
        if (hasIssue && !forceIssue) {
            throw ApiException.conflict("存在放错货道/温度不符明细, 请先纠正或显式确认(forceIssue=true)");
        }

        for (ReplenishmentItem item : items) {
            if ("MOVED".equals(item.getStatus())) continue;
            Channel ch = channelRepository.findById(item.getChannelId()).orElseThrow();
            inventoryService.changeOnShelf(item.getBatchId(), ch.getId(), r.getCabinetId(),
                    item.getSku(), item.getQty(), C.L_REPLENISH, "REPLENISH_ITEM", item.getId(),
                    r.getStaffId());
            ProductBatch batch = batchRepository.findById(item.getBatchId()).orElseThrow();
            if (C.BATCH_IN_WAREHOUSE.equals(batch.getStatus())) {
                batch.setStatus(C.BATCH_ON_SHELF);
                batchRepository.save(batch);
            }
        }
        r.setStatus(hasIssue ? "HAS_ISSUE" : "CONFIRMED");
        r.setCompletedAt(Ids.now());
        replenishmentRepository.save(r);
        publisher.publish("REPLENISH_CONFIRMED", r.getOrderNo(), Map.of(
                "orderNo", r.getOrderNo(),
                "cabinetId", r.getCabinetId(),
                "itemCount", items.size(),
                "hasIssue", hasIssue));
        return r;
    }

    /** 纠正放错货道: 把明细目标货道改为正确货道(确认后生效时产生跨货道流水) */
    @Transactional
    public ReplenishmentItem correctItem(Long itemId, String correctChannelCode) {
        ReplenishmentItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("补货明细不存在"));
        Replenishment r = replenishmentRepository.findById(item.getReplenishmentId()).orElseThrow();
        Channel target = channelRepository.findByCabinetIdAndCode(r.getCabinetId(), correctChannelCode)
                .orElseThrow(() -> ApiException.notFound("目标货道不存在: " + correctChannelCode));
        ProductBatch batch = batchRepository.findById(item.getBatchId()).orElseThrow();
        if (!target.getTempType().equals(batch.getTempRequirement())) {
            throw ApiException.badRequest("目标货道温层 " + target.getTempType()
                    + " 与商品要求 " + batch.getTempRequirement() + " 不匹配");
        }
        if (target.getExpectedSku() != null && !target.getExpectedSku().equals(batch.getSku())) {
            throw ApiException.badRequest("目标货道期望SKU " + target.getExpectedSku()
                    + " 与批次SKU " + batch.getSku() + " 不一致");
        }
        Long fromChannel = item.getChannelId();
        boolean alreadyConfirmed = !"OPEN".equals(r.getStatus());
        item.setChannelId(target.getId());
        item.setStatus("OK");
        item.setIssueNote("已纠正至货道 " + correctChannelCode);
        itemRepository.save(item);
        // 若已上架, 产生跨货道移动流水
        if (alreadyConfirmed) {
            inventoryService.moveBetweenChannels(batch.getId(), fromChannel, target.getId(),
                    r.getCabinetId(), item.getSku(), item.getQty(), item.getId(), r.getStaffId());
        }
        return item;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Replenishment r = replenishmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("补货单不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("replenishment", r);
        m.put("items", itemRepository.findByReplenishmentId(id));
        return m;
    }
}
