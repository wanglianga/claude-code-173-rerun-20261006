package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.CategoryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CategoryAdjustmentRepository extends JpaRepository<CategoryAdjustment, Long> {
    List<CategoryAdjustment> findByCabinetIdOrderByCreatedAtDesc(Long cabinetId);
}
