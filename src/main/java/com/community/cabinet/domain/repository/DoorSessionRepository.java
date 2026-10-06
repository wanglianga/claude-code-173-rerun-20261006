package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.DoorSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DoorSessionRepository extends JpaRepository<DoorSession, Long> {
    Optional<DoorSession> findBySessionNo(String sessionNo);
    List<DoorSession> findByUserIdOrderByOpenedAtDesc(String userId);
    List<DoorSession> findByCabinetIdAndStatus(Long cabinetId, String status);
}
