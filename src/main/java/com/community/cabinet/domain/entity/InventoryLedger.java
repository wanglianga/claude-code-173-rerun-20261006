package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "inventory_ledger")
public class InventoryLedger {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long batchId;
    private Long channelId;
    private Long cabinetId;
    private String sku;
    private Integer changeQty;
    private Integer balanceQty;
    private String reasonCode;
    private String refType;
    private Long refId;
    private Long operatorId;
    private LocalDateTime createdAt;
}
