package com.tempoapi.repository;

import com.tempoapi.entity.Space;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.UUID;
import java.util.Optional;
import java.time.LocalTime;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Page<Space> findByActiveTrue(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Space s WHERE s.id = :id")
    Optional<Space> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE spaces SET availability_revision = availability_revision + 1 WHERE id = :id", nativeQuery = true)
    int incrementAvailabilityRevision(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE spaces SET availability_revision = availability_revision + 1 "
            + "WHERE id = :id AND is_active = true AND opening_time <= :startTime AND closing_time >= :endTime", nativeQuery = true)
    int incrementRevisionIfBookable(@Param("id") UUID id, @Param("startTime") LocalTime startTime,
                                    @Param("endTime") LocalTime endTime);
}
