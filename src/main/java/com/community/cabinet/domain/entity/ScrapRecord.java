package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "scrap_record")
public class ScrapRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String scrapNo;
    private Long batchId;
    private Long cabinetId;
    private Long supplierId;
    private Integer qty;
    private BigDecimal costAmount;
    private String reason;
    private Boolean settled = false;
    private Long staffId;
    private LocalDateTime createdAt;
    private String note;
}
