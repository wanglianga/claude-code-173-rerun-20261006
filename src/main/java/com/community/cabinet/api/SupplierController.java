package com.community.cabinet.api;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.Staff;
import com.community.cabinet.service.BatchTraceService;
import com.community.cabinet.service.SettlementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "供应商门户", description = "供应商按批次查看损耗原因、按账期查看结算单")
@RestController
@RequestMapping("/api/supplier")
public class SupplierController {

    private final BatchTraceService traceService;
    private final SettlementService settlementService;

    public SupplierController(BatchTraceService traceService, SettlementService settlementService) {
        this.traceService = traceService;
        this.settlementService = settlementService;
    }

    @GetMapping("/batches/{batchNo}/trace")
    public Map<String, Object> trace(@PathVariable String batchNo) {
        Map<String, Object> view = traceService.trace(batchNo);
        // 供应商角色只能看自己批次; 财务可看全部
        Staff staff = StaffContext.get();
        if (C.ROLE_SUPPLIER.equals(staff.getRole())) {
            Object batchObj = view.get("batch");
            if (batchObj instanceof com.community.cabinet.domain.entity.ProductBatch b
                    && !b.getSupplierId().equals(staff.getSupplierId())) {
                throw ApiException.forbidden("无权查看其他供应商批次");
            }
        }
        return view;
    }

    @GetMapping("/settlements")
    public List<?> mySettlements() {
        Staff staff = StaffContext.get();
        Long supplierId = C.ROLE_SUPPLIER.equals(staff.getRole())
                ? staff.getSupplierId() : staff.getSupplierId();
        if (supplierId == null) throw ApiException.badRequest("当前账号未绑定供应商");
        return settlementService.bySupplier(supplierId);
    }
}
