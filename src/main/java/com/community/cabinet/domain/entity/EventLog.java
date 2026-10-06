package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "event_log")
public class EventLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String eventId;
    private String eventType;
    private String cabinetCode;
    private LocalDateTime occurredAt;
    private String payload;
    private Boolean processed = false;
    private LocalDateTime createdAt;
}
