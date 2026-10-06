package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "refund")
public class Refund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String refundNo;
    private Long orderId;
    private BigDecimal amount;
    private String reason;
    private String detail;
    private String status = "APPROVED";
    private Long staffId;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}
