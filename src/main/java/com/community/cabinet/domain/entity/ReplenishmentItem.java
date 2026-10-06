package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "replenishment_item")
public class ReplenishmentItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long replenishmentId;
    private Long batchId;
    private Long channelId;
    private String sku;
    private Integer qty;
    private LocalDate expiryDate;
    private String status = "OK";
    private String issueNote;
    private LocalDateTime createdAt;
}
