package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.ScrapRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ScrapRecordRepository extends JpaRepository<ScrapRecord, Long> {
    List<ScrapRecord> findByBatchIdOrderByCreatedAtAsc(Long batchId);
    List<ScrapRecord> findBySupplierIdAndSettled(Long supplierId, Boolean settled);
    List<ScrapRecord> findBySettled(Boolean settled);
    List<ScrapRecord> findByCabinetIdAndCreatedAtBetween(Long cabinetId, LocalDateTime from, LocalDateTime to);
}
