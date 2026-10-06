package com.community.cabinet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "batch_inventory")
public class BatchInventory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long batchId;
    private Long channelId;
    private Long cabinetId;
    private Integer onShelfQty = 0;
    private Integer quarantinedQty = 0;
    private Integer nearExpiryQty = 0;
    private Integer removedQty = 0;
    private Integer soldQty = 0;
    private Integer scrappedQty = 0;
    private LocalDateTime updatedAt;
}
