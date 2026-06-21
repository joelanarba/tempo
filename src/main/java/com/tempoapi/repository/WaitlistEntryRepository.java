package com.tempoapi.repository;

import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    List<WaitlistEntry> findByResourceIdAndStatusOrderByCreatedAtAsc(UUID resourceId, WaitlistStatus status);

    Page<WaitlistEntry> findByUserId(UUID userId, Pageable pageable);

    Optional<WaitlistEntry> findByHeldBooking_Id(UUID bookingId);

    @Query(value = "SELECT count(*) > 0 FROM waitlist_entries w "
            + "WHERE w.resource_id = :resourceId AND w.user_id = :userId "
            + "AND w.time_range = tstzrange(:startTime, :endTime, '[)') "
            + "AND w.status IN ('WAITING', 'OFFERED')",
            nativeQuery = true)
    boolean existsActiveEntry(@Param("resourceId") UUID resourceId,
                              @Param("userId") UUID userId,
                              @Param("startTime") OffsetDateTime startTime,
                              @Param("endTime") OffsetDateTime endTime);
}
