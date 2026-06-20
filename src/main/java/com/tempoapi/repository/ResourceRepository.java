package com.tempoapi.repository;

import com.tempoapi.entity.Resource;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    Page<Resource> findBySpaceIdAndActiveTrue(UUID spaceId, Pageable pageable);

    java.util.List<Resource> findBySpaceIdAndActiveTrueOrderByName(UUID spaceId);

    /**
     * Acquires a pessimistic write lock on the resource row (SELECT ... FOR UPDATE).
     * This serializes all concurrent booking attempts for the same resource: a second
     * transaction calling this for the same id blocks until the first commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Resource r WHERE r.id = :id")
    Optional<Resource> findByIdForUpdate(@Param("id") UUID id);
}
