package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.ReplenishmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReplenishmentItemRepository extends JpaRepository<ReplenishmentItem, Long> {
    List<ReplenishmentItem> findByReplenishmentId(Long replenishmentId);
}
