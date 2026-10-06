package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.ProductBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductBatchRepository extends JpaRepository<ProductBatch, Long> {
    Optional<ProductBatch> findByBatchNo(String batchNo);
    List<ProductBatch> findBySupplierId(Long supplierId);
    List<ProductBatch> findByExpiryDateLessThanEqualAndStatusIn(LocalDate date, List<String> statuses);
    List<ProductBatch> findBySku(String sku);
}
