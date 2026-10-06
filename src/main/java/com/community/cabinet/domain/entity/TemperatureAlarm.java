package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "temperature_alarm")
public class TemperatureAlarm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long cabinetId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    @Column(name = "peak_c")
    private BigDecimal peakC;
    private String status = "ACTIVE";
    private String actionTaken;
    private Long staffId;
    private String note;
}
