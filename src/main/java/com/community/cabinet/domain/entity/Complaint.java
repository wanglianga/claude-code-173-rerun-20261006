package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "complaint")
public class Complaint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String complaintNo;
    private String userId;
    private Long sessionId;
    private Long orderId;
    private String reason;
    private String status = "OPEN";
    private Long staffId;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private String resolutionNote;
}
