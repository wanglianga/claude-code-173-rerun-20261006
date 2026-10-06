package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "removal_record")
public class RemovalRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String removalNo;
    private Long cabinetId;
    private Long batchId;
    private Long channelId;
    private Integer qty;
    private String reason;
    private String status = "PENDING";
    private Long staffId;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
    private String note;
}
