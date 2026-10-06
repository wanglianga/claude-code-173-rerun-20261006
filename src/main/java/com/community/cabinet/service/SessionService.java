package com.community.cabinet.service;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import com.community.cabinet.service.recon.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 开门购物会话: 开门 -> 三模识别事件接入 -> 关门对账 -> 订单/库存/证据。
 * 证据不足时订单置 UNCERTAIN, 不自动扣款, 转人工复核(UNCERTAIN_ORDER 告警)。
 */
@Service
public class SessionService {

    private final DoorSessionRepository sessionRepository;
    private final RecognitionEventRepository eventRepository;
    private final SessionEvidenceRepository evidenceRepository;
    private final SaleOrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final ProductBatchRepository batchRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final AlertService alertService;
    private final EventPublisher publisher;
    private final RecognitionReconciler reconciler;
    private final InventoryLedgerRepository ledgerRepository;

    public SessionService(DoorSessionRepository sessionRepository,
                          RecognitionEventRepository eventRepository,
                          SessionEvidenceRepository evidenceRepository,
                          SaleOrderRepository orderRepository,
                          OrderItemRepository orderItemRepository,
                          CabinetRepository cabinetRepository,
                          ChannelRepository channelRepository,
                          BatchInventoryRepository inventoryRepository,
                          ProductBatchRepository batchRepository,
                          ProductRepository productRepository,
                          InventoryService inventoryService,
                          AlertService alertService,
                          EventPublisher publisher,
                          RecognitionReconciler reconciler,
                          InventoryLedgerRepository ledgerRepository) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.evidenceRepository = evidenceRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
        this.inventoryRepository = inventoryRepository;
        this.batchRepository = batchRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.alertService = alertService;
        this.publisher = publisher;
        this.reconciler = reconciler;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public DoorSession openSession(String cabinetCode, String userId, String videoClipUrl,
                                   Long clipStartMs) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetCode));
        DoorSession s = new DoorSession();
        s.setSessionNo(Ids.gen("DS"));
        s.setCabinetId(cabinet.getId());
        s.setUserId(userId);
        s.setOpenedAt(Ids.now());
        s.setStatus(C.SESSION_OPEN);
        s.setSettleStatus(C.SETTLE_PENDING);
        s.setVideoClipUrl(videoClipUrl);
        s.setClipStartMs(clipStartMs);
        s.setCreatedAt(Ids.now());
        s = sessionRepository.save(s);
        addEvidence(s.getId(), "DOOR_OPEN", null, s.getOpenedAt(), "用户开门 userId=" + userId);
        if (videoClipUrl != null) {
            addEvidence(s.getId(), "VIDEO_CLIP", videoClipUrl, s.getOpenedAt(), "关联视频片段起点");
        }
        publisher.publish("DOOR_OPENED", s.getSessionNo(), Map.of(
                "sessionNo", s.getSessionNo(), "cabinetCode", cabinetCode, "userId", userId));
        return s;
    }

    /** 接入一条原始识别事件(供设备网关与 Kafka 消费器共用, 调用方负责幂等) */
    @Transactional
    public RecognitionEvent ingestEvent(Long sessionId, String modality, String action,
                                        String channelCode, String sku, Long batchId,
                                        Integer weightDeltaG, BigDecimal confidence,
                                        String rawPayload, int eventNo) {
        DoorSession s = sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        Long channelId = null;
        if (channelCode != null) {
            channelId = channelRepository.findByCabinetIdAndCode(s.getCabinetId(), channelCode)
                    .map(Channel::getId).orElse(null);
        }
        RecognitionEvent e = new RecognitionEvent();
        e.setSessionId(sessionId);
        e.setEventNo(eventNo);
        e.setOccurredAt(Ids.now());
        e.setModality(modality);
        e.setAction(action);
        e.setChannelId(channelId);
        e.setSku(sku);
        e.setBatchId(batchId);
        e.setWeightDeltaG(weightDeltaG);
        e.setConfidence(confidence);
        e.setRawPayload(rawPayload);
        return eventRepository.save(e);
    }

    @Transactional
    public SaleOrder closeSession(String sessionNo, Long clipEndMs) {
        DoorSession s = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionNo));
        if (!C.SESSION_OPEN.equals(s.getStatus())) {
            throw ApiException.conflict("会话已关门结算");
        }
        s.setClosedAt(Ids.now());
        s.setStatus(C.SESSION_CLOSED);
        s.setClipEndMs(clipEndMs);
        addEvidence(s.getId(), "DOOR_CLOSE", null, s.getClosedAt(), "用户关门, 开始对账");

        List<RecognitionEvent> raws = eventRepository
                .findBySessionIdOrderByOccurredAtAscEventNoAsc(s.getId());
        List<ChannelStock> stocks = buildStocks(s.getCabinetId());
        List<RawEvent> rawEvents = raws.stream()
                .map(e -> new RawEvent(e.getEventNo(), e.getOccurredAt(), e.getModality(),
                        e.getAction(), e.getChannelId(), e.getSku(), e.getWeightDeltaG(),
                        e.getConfidence()))
                .toList();
        ReconcileResult result = reconciler.reconcile(rawEvents, stocks);

        // 回写每条事件的对账结论
        Map<Integer, Verdict> verdictMap = new HashMap<>();
        result.verdicts().forEach(v -> verdictMap.put(v.eventNo(), v));
        for (RecognitionEvent e : raws) {
            Verdict v = verdictMap.get(e.getEventNo());
            if (v != null) {
                e.setReconciledResult(v.result());
                e.setReconciledAt(Ids.now());
                eventRepository.save(e);
            }
        }

        // 建单
        SaleOrder order = new SaleOrder();
        order.setOrderNo(Ids.gen("SO"));
        order.setSessionId(s.getId());
        order.setCabinetId(s.getCabinetId());
        order.setUserId(s.getUserId());
        order.setUncertain(result.uncertain());
        order.setStatus(result.uncertain() ? C.ORDER_UNCERTAIN : C.ORDER_CONFIRMED);
        order.setEvidenceSummary(result.summary());
        order.setCreatedAt(Ids.now());
        order.setSettledAt(result.uncertain() ? null : Ids.now());
        order = orderRepository.save(order);

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> savedItems = new ArrayList<>();
        for (ReconciledLine line : result.lines()) {
            BigDecimal price = line.sku() == null ? BigDecimal.ZERO
                    : productRepository.findBySku(line.sku())
                            .map(Product::getUnitPrice).orElse(BigDecimal.ZERO);
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setSku(line.sku());
            item.setBatchId(line.batchId());
            item.setChannelId(line.channelId());
            item.setQty(line.qty());
            item.setUnitPrice(price);
            item.setSubtotal(price.multiply(BigDecimal.valueOf(line.qty())));
            if (line.uncertain()) {
                item.setStatus("PENDING_REVIEW");
            } else {
                item.setStatus(C.ITEM_CHARGED);
                total = total.add(item.getSubtotal());
            }
            item = orderItemRepository.save(item);
            savedItems.add(item);
            // 确定拿取: 扣库存、记售卖流水(流水引用明细, 供证据回放)
            if (!line.uncertain() && line.batchId() != null) {
                inventoryService.sale(line.batchId(), line.channelId(), s.getCabinetId(),
                        line.sku(), line.qty(), item.getId());
            }
        }
        order.setTotalAmount(total);
        orderRepository.save(order);

        s.setOrderId(order.getId());
        s.setAmount(total);
        s.setUncertainty(result.uncertain());
        s.setSettleStatus(result.uncertain() ? C.SETTLE_PENDING : C.SETTLE_SETTLED);
        sessionRepository.save(s);

        if (result.uncertain()) {
            alertService.warn(s.getCabinetId(), C.ALERT_UNCERTAIN_ORDER, "ORDER", order.getId(),
                    "订单 " + order.getOrderNo() + " 存在证据不足明细: " + result.summary());
        }
        publisher.publish("ORDER_SETTLED", order.getOrderNo(), Map.of(
                "orderNo", order.getOrderNo(),
                "userId", order.getUserId(),
                "amount", total.toPlainString(),
                "uncertain", result.uncertain(),
                "summary", result.summary()));
        return order;
    }

    /** 人工复核证据不足的明细: confirm=true 计售卖扣款, false 判不计费 */
    @Transactional
    public SaleOrder reviewOrder(Long orderId, boolean confirm, String note, Long staffId) {
        SaleOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        BigDecimal added = BigDecimal.ZERO;
        for (OrderItem item : items) {
            if (!"PENDING_REVIEW".equals(item.getStatus())) continue;
            if (confirm) {
                // 证据不足行此前无法指定批次, 复核确认时按 FEFO 从该货道在架库存指定
                if (item.getBatchId() == null && item.getChannelId() != null && item.getSku() != null) {
                    item.setBatchId(pickFefoBatch(item.getChannelId(), item.getSku()));
                }
                item.setStatus(C.ITEM_CHARGED);
                added = added.add(item.getSubtotal());
                if (item.getBatchId() != null) {
                    inventoryService.sale(item.getBatchId(), item.getChannelId(), order.getCabinetId(),
                            item.getSku(), item.getQty(), item.getId());
                }
            } else {
                item.setStatus("REJECTED");
            }
            orderItemRepository.save(item);
        }
        order.setTotalAmount(order.getTotalAmount().add(added));
        boolean anyPending = orderItemRepository.findByOrderId(orderId).stream()
                .anyMatch(i -> "PENDING_REVIEW".equals(i.getStatus()));
        if (!anyPending) {
            long charged = orderItemRepository.findByOrderId(orderId).stream()
                    .filter(i -> C.ITEM_CHARGED.equals(i.getStatus())).count();
            order.setStatus(charged > 0 ? C.ORDER_CONFIRMED : C.ORDER_CANCELLED);
            order.setUncertain(false);
            order.setSettledAt(Ids.now());
            DoorSession s = sessionRepository.findById(order.getSessionId()).orElseThrow();
            s.setSettleStatus(charged > 0 ? C.SETTLE_ADJUSTED : C.SETTLE_ADJUSTED);
            s.setAmount(order.getTotalAmount());
            s.setUncertainty(false);
            sessionRepository.save(s);
        }
        if (note != null) {
            order.setEvidenceSummary(order.getEvidenceSummary() + " | 人工复核("
                    + (confirm ? "确认计费" : "判定不计费") + "): " + note);
        }
        orderRepository.save(order);
        publisher.publish("ORDER_REVIEWED", order.getOrderNo(), Map.of(
                "orderNo", order.getOrderNo(), "confirmed", confirm, "note", String.valueOf(note)));
        return order;
    }

    /** 客服证据回放视图: 开门记录/识别事件及结论/视频/库存变化 */
    @Transactional(readOnly = true)
    public Map<String, Object> evidenceReplay(String sessionNo) {
        DoorSession s = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionNo));
        List<RecognitionEvent> events = eventRepository
                .findBySessionIdOrderByOccurredAtAscEventNoAsc(s.getId());
        List<SessionEvidence> evidences = evidenceRepository
                .findBySessionIdOrderByOccurredAtAsc(s.getId());

        // 会话时间窗内相关货道的库存变化(可回放)
        java.time.LocalDateTime from = s.getOpenedAt().minusSeconds(1);
        java.time.LocalDateTime to = s.getClosedAt() != null ? s.getClosedAt().plusMinutes(1)
                : Ids.now().plusMinutes(1);
        Set<Long> channelIds = events.stream().map(RecognitionEvent::getChannelId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<InventoryLedger> ledgers = new ArrayList<>();
        for (Long chId : channelIds) {
            ledgers.addAll(ledgerRepository
                    .findByChannelIdAndCreatedAtBetweenOrderByCreatedAtAsc(chId, from, to));
        }
        ledgers.sort(Comparator.comparing(InventoryLedger::getCreatedAt));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("session", s);
        view.put("events", events);
        view.put("evidences", evidences);
        view.put("inventoryChanges", ledgers);
        if (s.getOrderId() != null) {
            SaleOrder order = orderRepository.findById(s.getOrderId()).orElse(null);
            view.put("order", order);
            if (order != null) {
                view.put("orderItems", orderItemRepository.findByOrderId(order.getId()));
            }
        }
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingUncertainOrders() {
        return orderRepository.findByStatus(C.ORDER_UNCERTAIN).stream()
                .<Map<String, Object>>map(o -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("order", o);
                    m.put("items", orderItemRepository.findByOrderId(o.getId()));
                    return m;
                }).toList();
    }

    /** 复核确认时按 FEFO 指定货道在架批次 */
    private Long pickFefoBatch(Long channelId, String sku) {
        return inventoryRepository.findByChannelId(channelId).stream()
                .filter(i -> i.getOnShelfQty() > 0)
                .map(i -> batchRepository.findById(i.getBatchId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(b -> b.getSku().equals(sku))
                .min(Comparator.comparing(ProductBatch::getExpiryDate))
                .map(ProductBatch::getId)
                .orElse(null);
    }

    private List<ChannelStock> buildStocks(Long cabinetId) {        List<ChannelStock> result = new ArrayList<>();
        for (BatchInventory inv : inventoryRepository.findByCabinetId(cabinetId)) {
            if (inv.getOnShelfQty() <= 0) continue;
            ProductBatch b = batchRepository.findById(inv.getBatchId()).orElse(null);
            if (b == null) continue;
            result.add(new ChannelStock(b.getId(), inv.getChannelId(), b.getSku(),
                    b.getExpiryDate(), inv.getOnShelfQty()));
        }
        return result;
    }

    private void addEvidence(Long sessionId, String type, String url,
                             java.time.LocalDateTime at, String note) {
        SessionEvidence ev = new SessionEvidence();
        ev.setSessionId(sessionId);
        ev.setEvidenceType(type);
        ev.setUrl(url);
        ev.setOccurredAt(at);
        ev.setNote(note);
        evidenceRepository.save(ev);
    }
}
