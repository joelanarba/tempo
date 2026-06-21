package com.tempoapi.repository;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    Page<Booking> findByUserIdAndStatus(UUID userId, BookingStatus status, Pageable pageable);

    long countByResourceIdAndStatus(UUID resourceId, BookingStatus status);

    /**
     * Returns all bookings in a space that currently occupy the window — CONFIRMED bookings
     * and live HELD holds — ordered by resource then start time. Backs the availability
     * snapshot, so a held slot reads as occupied.
     */
    @Query(value = "SELECT b.* FROM bookings b "
            + "WHERE b.space_id = :spaceId "
            + "AND b.status IN ('CONFIRMED', 'HELD') "
            + "AND b.time_range && tstzrange(:startTime, :endTime, '[)') "
            + "ORDER BY b.resource_id, lower(b.time_range)",
            nativeQuery = true)
    List<Booking> findOccupiedInSpaceForWindow(@Param("spaceId") UUID spaceId,
                                               @Param("startTime") OffsetDateTime startTime,
                                               @Param("endTime") OffsetDateTime endTime);

    /**
     * Returns CONFIRMED or HELD bookings on the resource whose time range overlaps the
     * requested {@code [start, end)} range. Uses the Postgres range overlap operator
     * {@code &&}; the half-open literal means touching slots are not counted as
     * overlapping.
     */
    @Query(value = "SELECT b.* FROM bookings b "
            + "WHERE b.resource_id = :resourceId "
            + "AND b.status IN ('CONFIRMED', 'HELD') "
            + "AND b.time_range && tstzrange(:startTime, :endTime, '[)')",
            nativeQuery = true)
    List<Booking> findOverlapping(@Param("resourceId") UUID resourceId,
                                  @Param("startTime") OffsetDateTime startTime,
                                  @Param("endTime") OffsetDateTime endTime);

    /** Pessimistic write lock on a single booking row; serializes claim vs. sweeper-expiry. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /** Holds whose expiry has passed, oldest first; drives the scheduled sweep. */
    @Query(value = "SELECT b.* FROM bookings b "
            + "WHERE b.status = 'HELD' AND b.hold_expires_at < :now "
            + "ORDER BY b.hold_expires_at ASC",
            nativeQuery = true)
    List<Booking> findExpiredHolds(@Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * Returns every booking in a space whose time range overlaps the given window,
     * ordered by start time. Used by the admin "bookings for a date" view.
     */
    @Query(value = "SELECT b.* FROM bookings b "
            + "WHERE b.space_id = :spaceId "
            + "AND b.time_range && tstzrange(:startTime, :endTime, '[)') "
            + "ORDER BY lower(b.time_range)",
            countQuery = "SELECT count(*) FROM bookings b "
                    + "WHERE b.space_id = :spaceId "
                    + "AND b.time_range && tstzrange(:startTime, :endTime, '[)')",
            nativeQuery = true)
    Page<Booking> findInSpaceForWindow(@Param("spaceId") UUID spaceId,
                                       @Param("startTime") OffsetDateTime startTime,
                                       @Param("endTime") OffsetDateTime endTime,
                                       Pageable pageable);
}
