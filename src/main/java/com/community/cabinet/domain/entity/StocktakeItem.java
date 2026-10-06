package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity @Table(name = "stocktake_item")
public class StocktakeItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long stocktakeId;
    private Long batchId;
    private Long channelId;
    private Integer systemQty;
    private Integer countedQty;
    private Integer diffQty;
    private String reason = "OK";
    private Boolean handled = false;
    private String note;
}
