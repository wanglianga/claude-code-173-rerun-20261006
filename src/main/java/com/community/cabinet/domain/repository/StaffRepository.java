package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Staff;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {
    Optional<Staff> findByToken(String token);
}
