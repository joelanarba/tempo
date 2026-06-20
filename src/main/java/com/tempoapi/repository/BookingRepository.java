package com.tempoapi.repository;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    Page<Booking> findByUserIdAndStatus(UUID userId, BookingStatus status, Pageable pageable);

    long countByResourceIdAndStatus(UUID resourceId, BookingStatus status);

    /**
     * Returns all CONFIRMED bookings in a space whose time range overlaps the window,
     * ordered by resource then start time. Backs the availability snapshot.
     */
    @Query(value = "SELECT b.* FROM bookings b "
            + "WHERE b.space_id = :spaceId "
            + "AND b.status = 'CONFIRMED' "
            + "AND b.time_range && tstzrange(:startTime, :endTime, '[)') "
            + "ORDER BY b.resource_id, lower(b.time_range)",
            nativeQuery = true)
    List<Booking> findConfirmedInSpaceForWindow(@Param("spaceId") UUID spaceId,
                                                @Param("startTime") OffsetDateTime startTime,
                                                @Param("endTime") OffsetDateTime endTime);

    /**
     * Returns CONFIRMED bookings on the resource whose time range overlaps the
     * requested {@code [start, end)} range. Uses the Postgres range overlap operator
     * {@code &&}; the half-open literal means touching slots are not counted as
     * overlapping.
     */
    @Query(value = "SELECT b.* FROM bookings b "
            + "WHERE b.resource_id = :resourceId "
            + "AND b.status = 'CONFIRMED' "
            + "AND b.time_range && tstzrange(:startTime, :endTime, '[)')",
            nativeQuery = true)
    List<Booking> findOverlapping(@Param("resourceId") UUID resourceId,
                                  @Param("startTime") OffsetDateTime startTime,
                                  @Param("endTime") OffsetDateTime endTime);

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
