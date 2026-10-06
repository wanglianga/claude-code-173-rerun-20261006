package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "product_batch")
public class ProductBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String batchNo;
    private String sku;
    private Long supplierId;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private String tempRequirement;
    private Integer inboundQty;
    private BigDecimal unitCost;
    private String status = "IN_WAREHOUSE";
    private LocalDateTime createdAt;
}
