package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.RemovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RemovalRecordRepository extends JpaRepository<RemovalRecord, Long> {
    List<RemovalRecord> findByStatus(String status);
    List<RemovalRecord> findByBatchIdOrderByCreatedAtAsc(Long batchId);
    List<RemovalRecord> findByCabinetIdAndStatus(Long cabinetId, String status);
    List<RemovalRecord> findByCabinetIdAndCreatedAtBetween(Long cabinetId,
            java.time.LocalDateTime from, java.time.LocalDateTime to);
}
