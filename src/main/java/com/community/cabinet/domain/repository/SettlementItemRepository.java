package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {
    List<SettlementItem> findBySettlementId(Long settlementId);
}
