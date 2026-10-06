package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Stocktake;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StocktakeRepository extends JpaRepository<Stocktake, Long> {
}
