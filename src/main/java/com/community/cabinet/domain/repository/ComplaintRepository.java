package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Complaint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByStatus(String status);
    List<Complaint> findByUserIdOrderByCreatedAtDesc(String userId);
}
