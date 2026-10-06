package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "settlement")
public class Settlement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String settlementNo;
    private Long supplierId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String status = "DRAFT";
    private BigDecimal saleAmount = BigDecimal.ZERO;
    private BigDecimal scrapAmount = BigDecimal.ZERO;
    private BigDecimal payableAmount = BigDecimal.ZERO;
    private Long staffId;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private String note;
}
