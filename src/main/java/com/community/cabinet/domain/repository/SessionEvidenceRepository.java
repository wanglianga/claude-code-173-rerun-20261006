package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.SessionEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SessionEvidenceRepository extends JpaRepository<SessionEvidence, Long> {
    List<SessionEvidence> findBySessionIdOrderByOccurredAtAsc(Long sessionId);
}
