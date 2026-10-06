package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 货道卡货: 用户拿取时商品卡在货道。补货/运维到场处置:
 * - RESOLVED_RETURNED 商品完好取回重新上架(若该会话已计费则冲销订单明细并回库);
 * - SCRAPPED 商品损坏报损;
 * - IGNORED 误报。
 */
@Service
public class JamService {

    private final JamEventRepository jamRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final ProductBatchRepository batchRepository;
    private final OrderItemRepository orderItemRepository;
    private final SaleOrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final ScrapService scrapService;
    private final AlertService alertService;
    private final EventPublisher publisher;

    public JamService(JamEventRepository jamRepository, CabinetRepository cabinetRepository,
                      ChannelRepository channelRepository, BatchInventoryRepository inventoryRepository,
                      ProductBatchRepository batchRepository, OrderItemRepository orderItemRepository,
                      SaleOrderRepository orderRepository, InventoryService inventoryService,
                      ScrapService scrapService, AlertService alertService, EventPublisher publisher) {
        this.jamRepository = jamRepository;
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
        this.inventoryRepository = inventoryRepository;
        this.batchRepository = batchRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.scrapService = scrapService;
        this.alertService = alertService;
        this.publisher = publisher;
    }

    @Transactional
    public JamEvent report(String cabinetCode, String channelCode, String batchNo,
                           String sessionNo, int qty, String note) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        Channel channel = channelRepository.findByCabinetIdAndCode(cabinet.getId(), channelCode)
                .orElseThrow(() -> ApiException.notFound("货道不存在"));
        ProductBatch batch = batchNo == null ? null
                : batchRepository.findByBatchNo(batchNo).orElseThrow(() -> ApiException.notFound("批次不存在"));
        JamEvent jam = new JamEvent();
        jam.setCabinetId(cabinet.getId());
        jam.setChannelId(channel.getId());
        jam.setBatchId(batch == null ? null : batch.getId());
        jam.setQty(qty);
        jam.setOccurredAt(Ids.now());
        jam.setStatus("OPEN");
        jam.setNote(note);
        jam = jamRepository.save(jam);
        alertService.warn(cabinet.getId(), C.ALERT_JAM, "JAM_EVENT", jam.getId(),
                "货道 " + channelCode + " 卡货 " + qty + " 件");
        publisher.publish("JAM_REPORTED", String.valueOf(cabinet.getId()), Map.of(
                "jamId", jam.getId(), "channel", channelCode, "qty", qty));
        return jam;
    }

    /** 处置卡货 */
    @Transactional
    public JamEvent resolve(Long jamId, String resolution, Long staffId, String note) {
        JamEvent jam = jamRepository.findById(jamId)
                .orElseThrow(() -> ApiException.notFound("卡货事件不存在"));
        if (!"OPEN".equals(jam.getStatus())) throw ApiException.conflict("卡货事件已处置");
        String sku = jam.getBatchId() == null ? null
                : batchRepository.findById(jam.getBatchId()).map(ProductBatch::getSku).orElse(null);

        switch (resolution) {
            case "RESOLVED_RETURNED" -> {
                // 商品完好取回: 回架(若已被扣售则冲销售卖)
                if (jam.getBatchId() != null) {
                    boolean exists = inventoryRepository
                            .findByBatchIdAndChannelId(jam.getBatchId(), jam.getChannelId()).isPresent();
                    if (exists) {
                        // 若该货道该批次 soldQty 中确有本会话售卖, reverseSale 回库; 否则直接上架
                        BatchInventory inv = inventoryRepository
                                .findByBatchIdAndChannelId(jam.getBatchId(), jam.getChannelId()).get();
                        if (inv.getSoldQty() >= jam.getQty()) {
                            inventoryService.reverseSale(jam.getBatchId(), jam.getChannelId(),
                                    jam.getCabinetId(), sku, jam.getQty(), C.L_JAM_RECOVER,
                                    "JAM_EVENT", jamId, staffId);
                        } else {
                            inventoryService.changeOnShelf(jam.getBatchId(), jam.getChannelId(),
                                    jam.getCabinetId(), sku, jam.getQty(), C.L_JAM_RECOVER,
                                    "JAM_EVENT", jamId, staffId);
                        }
                    }
                }
                jam.setStatus("RESOLVED_RETURNED");
            }
            case "SCRAPPED" -> {
                if (jam.getBatchId() == null) throw ApiException.badRequest("无批次信息无法报损");
                scrapService.scrapOnShelf(jam.getBatchId(), jam.getCabinetId(), jam.getChannelId(),
                        "JAM_DAMAGE", staffId, "卡货损坏: " + note, jam.getQty());
                jam.setStatus("SCRAPPED");
            }
            case "IGNORED" -> jam.setStatus("IGNORED");
            default -> throw ApiException.badRequest("未知处置方式: " + resolution);
        }
        jam.setResolvedAt(Ids.now());
        jam.setStaffId(staffId);
        jam.setNote(note);
        jamRepository.save(jam);
        publisher.publish("JAM_RESOLVED", String.valueOf(jam.getId()), Map.of(
                "jamId", jamId, "resolution", resolution));
        return jam;
    }

    @Transactional(readOnly = true)
    public List<JamEvent> openJams() {
        return jamRepository.findByStatus("OPEN");
    }
}
