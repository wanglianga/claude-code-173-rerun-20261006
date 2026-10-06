package com.community.cabinet.api;

import com.community.cabinet.service.BatchTraceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "批次追溯", description = "批次补货/售卖/下架/报损/结算全链路")
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final BatchTraceService service;

    public TraceController(BatchTraceService service) { this.service = service; }

    @GetMapping("/batches/{batchNo}")
    public Map<String, Object> trace(@PathVariable String batchNo) {
        return service.trace(batchNo);
    }
}
