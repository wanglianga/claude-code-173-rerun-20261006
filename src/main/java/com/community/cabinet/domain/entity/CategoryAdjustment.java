package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "category_adjustment")
public class CategoryAdjustment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long cabinetId;
    private Long channelId;
    private String oldExpectedSku;
    private String newExpectedSku;
    private String reason;
    private Long staffId;
    private Boolean applied = false;
    private LocalDateTime createdAt;
    private LocalDateTime appliedAt;
}
