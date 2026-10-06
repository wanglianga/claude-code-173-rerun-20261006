package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "operation_alert")
public class OperationAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String alertType;
    private Long cabinetId;
    private String refType;
    private Long refId;
    private String severity = "WARN";
    private String message;
    private String status = "OPEN";
    private Long staffId;
    private LocalDateTime createdAt;
    private LocalDateTime acknowledgedAt;
}
