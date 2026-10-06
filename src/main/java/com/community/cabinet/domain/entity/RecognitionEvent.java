package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "recognition_event")
public class RecognitionEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long sessionId;
    private Integer eventNo;
    private LocalDateTime occurredAt;
    private String modality;
    private String action;
    private Long channelId;
    private String sku;
    private Long batchId;
    private BigDecimal confidence;
    @Column(name = "weight_delta_g")
    private Integer weightDeltaG;
    private String rawPayload;
    private String reconciledResult;
    private LocalDateTime reconciledAt;
}
