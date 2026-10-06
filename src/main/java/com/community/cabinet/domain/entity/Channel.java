package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity @Table(name = "channel")
public class Channel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long cabinetId;
    private String code;
    private String expectedSku;
    private Integer capacity = 10;
    private String tempType;
    private Boolean active = true;
}
