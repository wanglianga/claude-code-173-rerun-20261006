package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.SaleOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {
    List<SaleOrder> findByUserIdOrderByCreatedAtDesc(String userId);
    List<SaleOrder> findByStatus(String status);
    List<SaleOrder> findByCabinetIdAndCreatedAtBetween(Long cabinetId, LocalDateTime from, LocalDateTime to);
}
