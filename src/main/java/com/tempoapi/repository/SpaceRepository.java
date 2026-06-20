package com.tempoapi.repository;

import com.tempoapi.entity.Space;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Page<Space> findByActiveTrue(Pageable pageable);
}
