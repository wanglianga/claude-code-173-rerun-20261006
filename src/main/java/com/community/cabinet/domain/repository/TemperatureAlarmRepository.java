package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.TemperatureAlarm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TemperatureAlarmRepository extends JpaRepository<TemperatureAlarm, Long> {
    Optional<TemperatureAlarm> findFirstByCabinetIdAndStatusOrderByStartedAtDesc(Long cabinetId, String status);
    List<TemperatureAlarm> findByStatus(String status);
}
