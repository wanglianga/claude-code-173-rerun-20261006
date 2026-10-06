package com.community.cabinet.api;

import com.community.cabinet.common.Ids;
import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.DoorSession;
import com.community.cabinet.domain.entity.RecognitionEvent;
import com.community.cabinet.domain.entity.SaleOrder;
import com.community.cabinet.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "购物会话与订单", description = "开门/识别事件/关门结算/证据回放/人工复核")
@RestController
@RequestMapping("/api")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public record OpenReq(@NotBlank String cabinetCode, @NotBlank String userId,
                          String videoClipUrl, Long clipStartMs) {}
    public record RecognitionReq(@NotBlank String modality, @NotBlank String action,
                                 String channelCode, String sku, Integer weightDeltaG,
                                 BigDecimal confidence, String rawPayload, Integer seq) {}
    public record CloseReq(Long clipEndMs) {}
    public record ReviewReq(@NotNull Boolean confirm, String note) {}

    @Operation(summary = "用户开门(手动/测试入口, 设备一般走网关)")
    @PostMapping("/sessions/open")
    public DoorSession open(@Valid @RequestBody OpenReq req) {
        return sessionService.openSession(req.cabinetCode(), req.userId(),
                req.videoClipUrl(), req.clipStartMs());
    }

    @Operation(summary = "接入一条三模识别事件")
    @PostMapping("/sessions/{sessionId}/events")
    public RecognitionEvent recognition(@PathVariable Long sessionId,
                                        @Valid @RequestBody RecognitionReq req) {
        return sessionService.ingestEvent(sessionId, req.modality(), req.action(),
                req.channelCode(), req.sku(), null, req.weightDeltaG(), req.confidence(),
                req.rawPayload(), req.seq() == null ? 1 : req.seq());
    }

    @Operation(summary = "关门并对账结算")
    @PostMapping("/sessions/{sessionNo}/close")
    public SaleOrder close(@PathVariable String sessionNo, @RequestBody(required = false) CloseReq req) {
        return sessionService.closeSession(sessionNo, req == null ? null : req.clipEndMs());
    }

    @Operation(summary = "证据回放: 开门记录/识别结果/视频/库存变化")
    @GetMapping("/sessions/{sessionNo}/replay")
    public Map<String, Object> replay(@PathVariable String sessionNo) {
        return sessionService.evidenceReplay(sessionNo);
    }

    @Operation(summary = "证据不足订单列表(待人工复核)")
    @GetMapping("/orders/review/pending")
    public List<Map<String, Object>> pendingReview() {
        return sessionService.pendingUncertainOrders();
    }

    @Operation(summary = "人工复核证据不足订单")
    @PostMapping("/orders/{orderId}/review")
    public SaleOrder review(@PathVariable Long orderId, @Valid @RequestBody ReviewReq req) {
        return sessionService.reviewOrder(orderId, req.confirm(), req.note(), StaffContext.staffId());
    }
}
