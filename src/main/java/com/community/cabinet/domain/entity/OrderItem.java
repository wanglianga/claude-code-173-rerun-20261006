package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter
@Entity @Table(name = "order_item")
public class OrderItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private String sku;
    private Long batchId;
    private Long channelId;
    private Integer qty;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    private String status = "CHARGED";
}
