package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    List<Settlement> findBySupplierIdOrderByPeriodEndDesc(Long supplierId);
    boolean existsBySupplierIdAndPeriodStartAndPeriodEnd(Long supplierId, LocalDate s, LocalDate e);
}
