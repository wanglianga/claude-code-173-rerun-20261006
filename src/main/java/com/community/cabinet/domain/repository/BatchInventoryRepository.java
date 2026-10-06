package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.BatchInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BatchInventoryRepository extends JpaRepository<BatchInventory, Long> {
    Optional<BatchInventory> findByBatchIdAndChannelId(Long batchId, Long channelId);
    List<BatchInventory> findByBatchId(Long batchId);
    List<BatchInventory> findByCabinetId(Long cabinetId);
    List<BatchInventory> findByChannelId(Long channelId);
}
