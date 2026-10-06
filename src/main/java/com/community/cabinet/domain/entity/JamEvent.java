package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "jam_event")
public class JamEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long cabinetId;
    private Long channelId;
    private Long batchId;
    private Long sessionId;
    private Integer qty = 1;
    private LocalDateTime occurredAt;
    private LocalDateTime resolvedAt;
    private String status = "OPEN";
    private Long staffId;
    private String note;
}
