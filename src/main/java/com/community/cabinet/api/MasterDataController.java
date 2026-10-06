package com.community.cabinet.api;

import com.community.cabinet.common.ApiException;
import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "主数据", description = "供应商/商品/批次/柜机/货道")
@RestController
@RequestMapping("/api/master")
public class MasterDataController {

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final ProductBatchRepository batchRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;

    public MasterDataController(SupplierRepository supplierRepository, ProductRepository productRepository,
                                ProductBatchRepository batchRepository, CabinetRepository cabinetRepository,
                                ChannelRepository channelRepository) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
    }

    public record SupplierReq(@NotBlank String name, String contact, String settlementAccount) {}
    public record ProductReq(@NotBlank String sku, @NotBlank String name, String category,
                             @NotNull BigDecimal unitPrice, @NotNull BigDecimal costPrice,
                             @NotBlank String tempRequirement) {}
    public record BatchReq(@NotBlank String batchNo, @NotBlank String sku, @NotNull Long supplierId,
                           @NotNull LocalDate productionDate, @NotNull LocalDate expiryDate,
                           @NotBlank String tempRequirement, @NotNull Integer inboundQty,
                           @NotNull BigDecimal unitCost) {}
    public record CabinetReq(@NotBlank String code, @NotBlank String name, @NotBlank String locationType,
                             String address, @NotBlank String tempType) {}
    public record ChannelReq(@NotBlank String code, String expectedSku, @NotNull Integer capacity,
                             @NotBlank String tempType) {}

    @PostMapping("/suppliers")
    public Supplier createSupplier(@Valid @RequestBody SupplierReq req) {
        Supplier s = new Supplier();
        s.setName(req.name()); s.setContact(req.contact()); s.setSettlementAccount(req.settlementAccount());
        s.setCreatedAt(Ids.now());
        return supplierRepository.save(s);
    }

    @GetMapping("/suppliers")
    public List<Supplier> suppliers() { return supplierRepository.findAll(); }

    @PostMapping("/products")
    public Product createProduct(@Valid @RequestBody ProductReq req) {
        Product p = new Product();
        p.setSku(req.sku()); p.setName(req.name()); p.setCategory(req.category());
        p.setUnitPrice(req.unitPrice()); p.setCostPrice(req.costPrice());
        p.setTempRequirement(req.tempRequirement()); p.setCreatedAt(Ids.now());
        return productRepository.save(p);
    }

    @GetMapping("/products")
    public List<Product> products() { return productRepository.findAll(); }

    @PostMapping("/batches")
    public ProductBatch createBatch(@Valid @RequestBody BatchReq req) {
        if (productRepository.findBySku(req.sku()).isEmpty()) throw ApiException.badRequest("SKU 不存在");
        if (supplierRepository.findById(req.supplierId()).isEmpty()) throw ApiException.badRequest("供应商不存在");
        ProductBatch b = new ProductBatch();
        b.setBatchNo(req.batchNo()); b.setSku(req.sku()); b.setSupplierId(req.supplierId());
        b.setProductionDate(req.productionDate()); b.setExpiryDate(req.expiryDate());
        b.setTempRequirement(req.tempRequirement()); b.setInboundQty(req.inboundQty());
        b.setUnitCost(req.unitCost()); b.setStatus(C.BATCH_IN_WAREHOUSE);
        b.setCreatedAt(Ids.now());
        return batchRepository.save(b);
    }

    @PostMapping("/cabinets")
    public Cabinet createCabinet(@Valid @RequestBody CabinetReq req) {
        Cabinet c = new Cabinet();
        c.setCode(req.code()); c.setName(req.name()); c.setLocationType(req.locationType());
        c.setAddress(req.address()); c.setTempType(req.tempType()); c.setStatus("NORMAL");
        c.setCreatedAt(Ids.now());
        return cabinetRepository.save(c);
    }

    @GetMapping("/cabinets")
    public List<Cabinet> cabinets() { return cabinetRepository.findAll(); }

    @PostMapping("/cabinets/{cabinetCode}/channels")
    public Channel createChannel(@PathVariable String cabinetCode, @Valid @RequestBody ChannelReq req) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        Channel ch = new Channel();
        ch.setCabinetId(cabinet.getId()); ch.setCode(req.code());
        ch.setExpectedSku(req.expectedSku()); ch.setCapacity(req.capacity());
        ch.setTempType(req.tempType());
        return channelRepository.save(ch);
    }

    @GetMapping("/cabinets/{cabinetCode}/channels")
    public List<Channel> channels(@PathVariable String cabinetCode) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        return channelRepository.findByCabinetId(cabinet.getId());
    }
}
