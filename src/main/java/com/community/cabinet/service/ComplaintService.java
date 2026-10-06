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
 * 客服售后: 受理投诉 -> 调取证据回放(开门/识别/视频/库存变化) ->
 * 误扣款退款(全额/部分, 可选回库冲销)。退款联动订单/会话状态与库存台账。
 */
@Service
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final RefundRepository refundRepository;
    private final SaleOrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final DoorSessionRepository sessionRepository;
    private final InventoryService inventoryService;
    private final ProductBatchRepository batchRepository;
    private final EventPublisher publisher;

    public ComplaintService(ComplaintRepository complaintRepository, RefundRepository refundRepository,
                            SaleOrderRepository orderRepository, OrderItemRepository orderItemRepository,
                            DoorSessionRepository sessionRepository, InventoryService inventoryService,
                            ProductBatchRepository batchRepository, EventPublisher publisher) {
        this.complaintRepository = complaintRepository;
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.sessionRepository = sessionRepository;
        this.inventoryService = inventoryService;
        this.batchRepository = batchRepository;
        this.publisher = publisher;
    }

    @Transactional
    public Complaint create(String userId, String sessionNo, String reason) {
        Complaint c = new Complaint();
        c.setComplaintNo(Ids.gen("CS"));
        c.setUserId(userId);
        c.setReason(reason);
        c.setStatus("OPEN");
        c.setCreatedAt(Ids.now());
        if (sessionNo != null) {
            DoorSession s = sessionRepository.findBySessionNo(sessionNo).orElse(null);
            if (s != null) {
                c.setSessionId(s.getId());
                c.setOrderId(s.getOrderId());
                s.setStatus(C.SESSION_DISPUTED);
                sessionRepository.save(s);
                if (s.getOrderId() != null) {
                    SaleOrder o = orderRepository.findById(s.getOrderId()).orElse(null);
                    if (o != null) {
                        o.setStatus(C.ORDER_DISPUTED);
                        orderRepository.save(o);
                    }
                }
            }
        }
        c = complaintRepository.save(c);
        publisher.publish("COMPLAINT_CREATED", c.getComplaintNo(), Map.of(
                "complaintNo", c.getComplaintNo(), "userId", userId));
        return c;
    }

    /**
     * 退款: amount 退款金额; restockItemIds 需要回库冲销的明细(用户实际未拿到商品)。
     * 全额退 -> REFUNDED; 部分退 -> PARTIALLY_REFUNDED。
     */
    @Transactional
    public Refund refund(Long complaintId, Long orderId, BigDecimal amount, String reason,
                         String detail, List<Long> restockItemIds, Long staffId) {
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("退款金额必须大于0");
        SaleOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (amount.compareTo(order.getTotalAmount()) > 0) {
            throw ApiException.badRequest("退款金额不能超过订单金额 " + order.getTotalAmount());
        }

        // 回库冲销: 误扣款且用户未拿走 -> 已售回架
        if (restockItemIds != null) {
            for (Long itemId : restockItemIds) {
                OrderItem item = orderItemRepository.findById(itemId)
                        .orElseThrow(() -> ApiException.notFound("订单明细不存在"));
                if (!item.getOrderId().equals(orderId)) throw ApiException.badRequest("明细不属于该订单");
                if (item.getBatchId() != null && C.ITEM_CHARGED.equals(item.getStatus())) {
                    inventoryService.reverseSale(item.getBatchId(), item.getChannelId(),
                            order.getCabinetId(), item.getSku(), item.getQty(),
                            C.L_SALE_REVERSED, "REFUND", null, staffId);
                }
                item.setStatus(C.ITEM_REFUNDED);
                orderItemRepository.save(item);
            }
        }

        Refund r = new Refund();
        r.setRefundNo(Ids.gen("RF"));
        r.setOrderId(orderId);
        r.setAmount(amount);
        r.setReason(reason);
        r.setDetail(detail);
        r.setStatus("DONE");
        r.setStaffId(staffId);
        r.setCreatedAt(Ids.now());
        r.setHandledAt(Ids.now());
        refundRepository.save(r);

        boolean full = amount.compareTo(order.getTotalAmount()) == 0;
        order.setStatus(full ? C.ORDER_REFUNDED : C.ORDER_PARTIAL_REFUNDED);
        orderRepository.save(order);

        DoorSession session = sessionRepository.findById(order.getSessionId()).orElseThrow();
        session.setSettleStatus(C.SETTLE_REFUNDED);
        sessionRepository.save(session);

        if (complaintId != null) {
            complaintRepository.findById(complaintId).ifPresent(c -> {
                c.setStatus("REFUNDED");
                c.setStaffId(staffId);
                c.setResolvedAt(Ids.now());
                c.setResolutionNote("退款 " + amount + "(" + reason + "): " + detail);
                complaintRepository.save(c);
            });
        }
        publisher.publish("REFUND_DONE", r.getRefundNo(), Map.of(
                "refundNo", r.getRefundNo(), "orderNo", order.getOrderNo(),
                "amount", amount.toPlainString(), "reason", reason));
        return r;
    }

    /** 核实无责关闭 */
    @Transactional
    public Complaint closeNoFault(Long complaintId, Long staffId, String note) {
        Complaint c = complaintRepository.findById(complaintId)
                .orElseThrow(() -> ApiException.notFound("投诉不存在"));
        c.setStatus("NO_FAULT");
        c.setStaffId(staffId);
        c.setResolvedAt(Ids.now());
        c.setResolutionNote("核实无责: " + note);
        return complaintRepository.save(c);
    }

    @Transactional(readOnly = true)
    public List<Complaint> openComplaints() {
        return complaintRepository.findByStatus("OPEN");
    }
}
