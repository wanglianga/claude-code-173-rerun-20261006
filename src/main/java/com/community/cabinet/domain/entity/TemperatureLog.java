package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "temperature_log")
public class TemperatureLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long cabinetId;
    private LocalDateTime recordedAt;
    @Column(name = "temp_c")
    private BigDecimal tempC;
    private String sensor;
    private String status;
}
