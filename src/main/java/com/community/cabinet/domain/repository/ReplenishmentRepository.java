package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Replenishment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReplenishmentRepository extends JpaRepository<Replenishment, Long> {
    List<Replenishment> findByCabinetId(Long cabinetId);
    List<Replenishment> findByCabinetIdAndArrivedAtBetween(Long cabinetId,
            java.time.LocalDateTime from, java.time.LocalDateTime to);
}
