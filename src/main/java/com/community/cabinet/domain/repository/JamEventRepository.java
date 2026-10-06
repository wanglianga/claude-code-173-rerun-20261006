package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.JamEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JamEventRepository extends JpaRepository<JamEvent, Long> {
    List<JamEvent> findByStatus(String status);
    List<JamEvent> findBySessionId(Long sessionId);
}
