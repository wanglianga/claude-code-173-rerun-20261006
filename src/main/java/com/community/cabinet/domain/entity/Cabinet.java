package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "cabinet")
public class Cabinet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String locationType;
    private String address;
    private String tempType;
    private String status = "NORMAL";
    private Boolean active = true;
    private LocalDateTime createdAt;
}
