package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "sale_order")
public class SaleOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String orderNo;
    private Long sessionId;
    private Long cabinetId;
    private String userId;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private String status = "PENDING";
    private Boolean uncertain = false;
    private String evidenceSummary;
    private LocalDateTime createdAt;
    private LocalDateTime settledAt;
}
