package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "stocktake")
public class Stocktake {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stocktakeNo;
    private Long cabinetId;
    private Long staffId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String status = "OPEN";
    private String note;
}
