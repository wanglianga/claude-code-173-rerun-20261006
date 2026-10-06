package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Cabinet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CabinetRepository extends JpaRepository<Cabinet, Long> {
    Optional<Cabinet> findByCode(String code);
}
