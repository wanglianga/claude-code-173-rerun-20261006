package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.InventoryLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InventoryLedgerRepository extends JpaRepository<InventoryLedger, Long> {
    List<InventoryLedger> findByBatchIdOrderByCreatedAtAsc(Long batchId);
    List<InventoryLedger> findByChannelIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            Long channelId, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
