package com.community.cabinet.api;

import com.community.cabinet.common.Ids;
import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.OperationAlert;
import com.community.cabinet.domain.repository.OperationAlertRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "运营告警", description = "温控/临期/卡货/盘亏/错放货道/低库存/证据不足订单告警")
@RestController
@RequestMapping("/api/operations/alerts")
public class AlertController {

    private final OperationAlertRepository repository;

    public AlertController(OperationAlertRepository repository) { this.repository = repository; }

    @GetMapping
    public List<OperationAlert> list(@RequestParam(defaultValue = "OPEN") String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status);
    }

    @PostMapping("/{id}/ack")
    public OperationAlert ack(@PathVariable Long id) {
        OperationAlert a = repository.findById(id).orElseThrow();
        a.setStatus("ACK");
        a.setStaffId(StaffContext.staffId());
        a.setAcknowledgedAt(Ids.now());
        return repository.save(a);
    }

    @PostMapping("/{id}/close")
    public OperationAlert close(@PathVariable Long id) {
        OperationAlert a = repository.findById(id).orElseThrow();
        a.setStatus("CLOSED");
        a.setStaffId(StaffContext.staffId());
        a.setAcknowledgedAt(Ids.now());
        return repository.save(a);
    }
}
