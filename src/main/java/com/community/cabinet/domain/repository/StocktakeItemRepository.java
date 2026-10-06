package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.StocktakeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StocktakeItemRepository extends JpaRepository<StocktakeItem, Long> {
    List<StocktakeItem> findByStocktakeId(Long stocktakeId);
}
