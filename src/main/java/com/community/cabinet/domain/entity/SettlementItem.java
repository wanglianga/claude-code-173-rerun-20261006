package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter
@Entity @Table(name = "settlement_item")
public class SettlementItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long settlementId;
    private Long batchId;
    private Integer soldQty;
    private BigDecimal saleCostAmount = BigDecimal.ZERO;
    private Integer scrappedQty;
    private BigDecimal scrapCostAmount = BigDecimal.ZERO;
    private BigDecimal payable = BigDecimal.ZERO;
}
