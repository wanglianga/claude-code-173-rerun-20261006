package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.TemperatureLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TemperatureLogRepository extends JpaRepository<TemperatureLog, Long> {
    List<TemperatureLog> findTop20ByCabinetIdOrderByRecordedAtDesc(Long cabinetId);
}
