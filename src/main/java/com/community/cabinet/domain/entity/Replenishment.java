package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "replenishment")
public class Replenishment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String orderNo;
    private Long cabinetId;
    private Long staffId;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private String status = "OPEN";
    private String photoUrl;
    private String note;
    private LocalDateTime createdAt;
}
