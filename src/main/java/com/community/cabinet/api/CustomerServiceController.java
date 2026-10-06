package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.Complaint;
import com.community.cabinet.domain.entity.Refund;
import com.community.cabinet.service.ComplaintService;
import com.community.cabinet.service.SessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "客服售后", description = "投诉受理/证据回放/误扣款退款/无责关闭")
@RestController
@RequestMapping("/api/cs")
public class CustomerServiceController {

    private final ComplaintService complaintService;
    private final SessionService sessionService;

    public CustomerServiceController(ComplaintService complaintService, SessionService sessionService) {
        this.complaintService = complaintService;
        this.sessionService = sessionService;
    }

    public record ComplaintReq(@NotBlank String userId, String sessionNo, @NotBlank String reason) {}
    public record RefundReq(@NotNull Long orderId, @NotNull BigDecimal amount, @NotBlank String reason,
                            String detail, List<Long> restockItemIds) {}
    public record CloseReq(@NotBlank String note) {}

    @PostMapping("/complaints")
    public Complaint create(@Valid @RequestBody ComplaintReq req) {
        return complaintService.create(req.userId(), req.sessionNo(), req.reason());
    }

    @GetMapping("/complaints/open")
    public List<Complaint> open() { return complaintService.openComplaints(); }

    @GetMapping("/sessions/{sessionNo}/replay")
    public Map<String, Object> replay(@PathVariable String sessionNo) {
        return sessionService.evidenceReplay(sessionNo);
    }

    @PostMapping("/complaints/{id}/refund")
    public Refund refund(@PathVariable Long id, @Valid @RequestBody RefundReq req) {
        return complaintService.refund(id, req.orderId(), req.amount(), req.reason(),
                req.detail(), req.restockItemIds(), StaffContext.staffId());
    }

    @PostMapping("/complaints/{id}/no-fault")
    public Complaint noFault(@PathVariable Long id, @Valid @RequestBody CloseReq req) {
        return complaintService.closeNoFault(id, StaffContext.staffId(), req.note());
    }
}
