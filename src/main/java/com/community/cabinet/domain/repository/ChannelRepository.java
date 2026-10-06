package com.community.cabinet.domain.repository;

import com.community.cabinet.domain.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    List<Channel> findByCabinetId(Long cabinetId);
    Optional<Channel> findByCabinetIdAndCode(Long cabinetId, String code);
}
