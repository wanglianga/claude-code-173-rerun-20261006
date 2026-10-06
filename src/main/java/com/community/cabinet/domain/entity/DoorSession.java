package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "door_session")
public class DoorSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sessionNo;
    private Long cabinetId;
    private String userId;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private String status = "OPEN";
    private String settleStatus = "PENDING";
    private BigDecimal amount = BigDecimal.ZERO;
    private Long orderId;
    private String videoClipUrl;
    @Column(name = "clip_start_ms")
    private Long clipStartMs;
    @Column(name = "clip_end_ms")
    private Long clipEndMs;
    private Boolean uncertainty = false;
    private LocalDateTime createdAt;
}
