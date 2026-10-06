package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.OperationAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationAlertRepository extends JpaRepository<OperationAlert, Long> {
    List<OperationAlert> findByStatusOrderByCreatedAtDesc(String status);
    boolean existsByAlertTypeAndRefTypeAndRefIdAndStatus(String alertType, String refType, Long refId, String status);
}
