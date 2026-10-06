package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.EventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {
    Optional<EventLog> findByEventId(String eventId);
}
