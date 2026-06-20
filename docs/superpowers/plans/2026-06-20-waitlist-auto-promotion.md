# Waitlist Auto-Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-resource, per-slot waitlist that, when a booking is cancelled or a hold expires, offers the freed slot FIFO to the next member as a time-bounded exclusive hold, delivered in realtime, with the offer claimable, declinable, and self-expiring.

**Architecture:** A hold is a real `bookings` row in a new `HELD` status, guarded by the existing GiST exclusion constraint (extended to `CONFIRMED|HELD`). Promotion reuses the booking core's pessimistic resource-row lock and the post-commit publish step. A scheduled sweep expires stale holds and cascades to the next waiter. Realtime offers go to a new user-targeted STOMP destination `/user/queue/waitlist`; the public topic still shows held slots as `BOOKED`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Data JPA, PostgreSQL (Neon) with `btree_gist`, Flyway, hypersistence-utils `Range<ZonedDateTime>`, Spring WebSocket/STOMP, Redis, JUnit 5 + Mockito (unit), Testcontainers Postgres + Redis (integration).

## Global Constraints

Copied verbatim from CLAUDE.md — every task inherits these:

- No Lombok — explicit constructors, getters, setters.
- No MapStruct — manual DTO mapping in the service/DTO layer.
- Constructor injection only — never field `@Autowired`.
- `ResponseEntity<ApiResponse<T>>` wrapper for all REST endpoints; `ApiResponse<T>` shape `{ success, message, data }`.
- Validation with Jakarta Bean Validation.
- Global exception handler maps domain exceptions to HTTP status codes.
- No `System.out.println` — SLF4J only.
- All timestamps UTC, `TIMESTAMP WITH TIME ZONE`; time slots as `TSTZRANGE`.
- UUID primary keys; BCrypt strength 12 (unchanged here).
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`); no `Co-Authored-By`; no mention of AI tools in commits, comments, or files; no emojis.
- Flyway owns the schema; Hibernate `ddl-auto=none`.
- `mvn test` green and no compiler warnings at the end of the sprint.

## Environment Note (Docker)

Docker is unavailable on the build laptop; Testcontainers integration tests run in GitHub Codespaces. In this plan:

- Tests marked **[local]** are pure unit tests (Mockito / plain objects) and must pass locally.
- Tests marked **[Codespaces]** use Testcontainers and are written + compiled locally but executed with `mvn test` in Codespaces.
- The local gate for every task is: `mvn -o -q test-compile` succeeds with no warnings, and all **[local]** tests pass via `mvn -o -q -Dtest=<ClassName> test`.

Set JDK 21 for every Maven command in PowerShell:
`$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"`

---

## File Structure

New files:

- `src/main/resources/db/migration/V7__bookings_add_hold.sql` — extend status + constraint, add `hold_expires_at`.
- `src/main/resources/db/migration/V8__create_waitlist_entries.sql` — waitlist table + indexes.
- `src/main/java/com/tempoapi/entity/WaitlistEntry.java` — JPA entity.
- `src/main/java/com/tempoapi/entity/WaitlistStatus.java` — enum.
- `src/main/java/com/tempoapi/repository/WaitlistEntryRepository.java` — Spring Data repo.
- `src/main/java/com/tempoapi/exception/OfferExpiredException.java`
- `src/main/java/com/tempoapi/exception/AlreadyWaitlistedException.java`
- `src/main/java/com/tempoapi/exception/SlotAvailableException.java`
- `src/main/java/com/tempoapi/websocket/WaitlistEventPublisher.java` — interface.
- `src/main/java/com/tempoapi/websocket/WaitlistState.java` — enum (OFFERED, GRANTED, EXPIRED).
- `src/main/java/com/tempoapi/websocket/WaitlistMessage.java` — record payload.
- `src/main/java/com/tempoapi/websocket/LoggingWaitlistPublisher.java`
- `src/main/java/com/tempoapi/websocket/WebSocketWaitlistPublisher.java` — `@Primary`.
- `src/main/java/com/tempoapi/modules/waitlist/WaitlistPromoter.java` — interface BookingService depends on.
- `src/main/java/com/tempoapi/modules/waitlist/WaitlistService.java` — core service (implements `WaitlistPromoter`).
- `src/main/java/com/tempoapi/modules/waitlist/WaitlistController.java`
- `src/main/java/com/tempoapi/modules/waitlist/WaitlistScheduler.java`
- `src/main/java/com/tempoapi/modules/waitlist/dto/JoinWaitlistRequest.java`
- `src/main/java/com/tempoapi/modules/waitlist/dto/WaitlistEntryResponse.java`
- Test files listed per task.

Modified files:

- `src/main/java/com/tempoapi/entity/BookingStatus.java` — add `HELD`, `EXPIRED`.
- `src/main/java/com/tempoapi/entity/Booking.java` — add `holdExpiresAt`.
- `src/main/java/com/tempoapi/repository/BookingRepository.java` — count `HELD` in overlap + availability; add `findByIdForUpdate`, `findExpiredHolds`.
- `src/main/java/com/tempoapi/modules/availability/AvailabilityService.java` — call renamed occupied query.
- `src/main/java/com/tempoapi/modules/bookings/BookingService.java` — inject `WaitlistPromoter`; cancel delegates to promotion.
- `src/main/java/com/tempoapi/config/WebSocketConfig.java` — broker `/queue`.
- `src/main/java/com/tempoapi/exception/GlobalExceptionHandler.java` — 410/409/400 handlers.
- `src/main/resources/application.properties` — `app.waitlist.*` props.
- `src/test/java/com/tempoapi/modules/bookings/BookingServiceTest.java` — add `WaitlistPromoter` mock + delegation test.
- `DEFENSE.md`, `README.md`, `docs/tempo-api.postman_collection.json`, `docs/websocket-testing.md`, `deploy/tempo.env.example`.

---

## Task 1: Booking gains HELD/EXPIRED and a hold expiry (entity + V7 migration + repo queries)

**Files:**
- Modify: `src/main/java/com/tempoapi/entity/BookingStatus.java`
- Modify: `src/main/java/com/tempoapi/entity/Booking.java`
- Create: `src/main/resources/db/migration/V7__bookings_add_hold.sql`
- Modify: `src/main/java/com/tempoapi/repository/BookingRepository.java`
- Modify: `src/main/java/com/tempoapi/modules/availability/AvailabilityService.java:67`
- Modify: `src/test/java/com/tempoapi/modules/availability/AvailabilityCacheTest.java:77,90`
- Test: `src/test/java/com/tempoapi/HeldBookingSchemaTest.java`

**Interfaces:**
- Produces: `BookingStatus.HELD`, `BookingStatus.EXPIRED`; `Booking.getHoldExpiresAt()`, `Booking.setHoldExpiresAt(Instant)`; `BookingRepository.findByIdForUpdate(UUID)`, `BookingRepository.findExpiredHolds(OffsetDateTime, Pageable)`; `BookingRepository.findOccupiedInSpaceForWindow(...)` (replaces `findConfirmedInSpaceForWindow`); `findOverlapping` now counts `CONFIRMED|HELD`.

- [ ] **Step 1: Add the two enum values**

Edit `BookingStatus.java` to:

```java
package com.tempoapi.entity;

public enum BookingStatus {
    CONFIRMED,
    CANCELLED,
    HELD,
    EXPIRED
}
```

- [ ] **Step 2: Add the hold expiry column to the entity**

In `Booking.java`, after the `cancelledAt` field (around line 61) add:

```java
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;
```

And after `setCancelledAt(...)` add the accessors:

```java
    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public void setHoldExpiresAt(Instant holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
    }
```

- [ ] **Step 3: Write the V7 migration**

Create `V7__bookings_add_hold.sql`:

```sql
-- A hold is a real booking row in HELD status. The exclusion constraint is widened to
-- cover HELD so a held slot is protected at the database level exactly like a confirmed
-- one; an EXPIRED hold releases that protection.

ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('CONFIRMED', 'CANCELLED', 'HELD', 'EXPIRED'));

ALTER TABLE bookings ADD COLUMN hold_expires_at TIMESTAMP WITH TIME ZONE;

-- A HELD row must always carry an expiry; nothing else may.
ALTER TABLE bookings
    ADD CONSTRAINT bookings_hold_expiry_check
    CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL);

ALTER TABLE bookings DROP CONSTRAINT bookings_no_overlap;
ALTER TABLE bookings
    ADD CONSTRAINT bookings_no_overlap
    EXCLUDE USING gist (resource_id WITH =, time_range WITH &&)
    WHERE (status IN ('CONFIRMED', 'HELD'));

-- Backs the scheduled sweep that finds holds past their expiry.
CREATE INDEX idx_bookings_active_holds ON bookings (hold_expires_at) WHERE status = 'HELD';
```

Note: the original `CHECK` and exclusion constraint were created inline in V6. The inline
status check is named `bookings_status_check` by Postgres convention (`<table>_status_check`);
if a Codespaces run reports a different generated name, adjust the `DROP CONSTRAINT` name to match
what `\d bookings` shows. The exclusion constraint is explicitly named `bookings_no_overlap` in V6.

- [ ] **Step 4: Update the booking repository queries**

In `BookingRepository.java`: add the `Pageable`/`LockModeType` imports, change the two status filters to include `HELD`, rename the availability query, and add the lock + sweep finders.

Replace `findConfirmedInSpaceForWindow` with:

```java
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
```

Change `findOverlapping`'s status line from `AND b.status = 'CONFIRMED' ` to:

```java
            + "AND b.status IN ('CONFIRMED', 'HELD') "
```

Add these methods (and `import jakarta.persistence.LockModeType;`, `import org.springframework.data.jpa.repository.Lock;`):

```java
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
```

Add `import java.util.Optional;` if not present.

- [ ] **Step 5: Point AvailabilityService and its spy test at the renamed query**

In `AvailabilityService.java:67` change `findConfirmedInSpaceForWindow` to `findOccupiedInSpaceForWindow` (same arguments).

The cache spy test verifies the old method name on `BookingRepository`. In
`AvailabilityCacheTest.java` change both occurrences (lines 77 and 90) from
`findConfirmedInSpaceForWindow` to `findOccupiedInSpaceForWindow`:

```java
        verify(bookingRepository, times(1)).findOccupiedInSpaceForWindow(eq(spaceId), any(), any());
```

```java
        verify(bookingRepository, times(2)).findOccupiedInSpaceForWindow(eq(spaceId), any(), any());
```

This rename is the reason a held slot now appears as occupied in the availability snapshot, which
the cache test still exercises unchanged.

- [ ] **Step 6: Write the schema test [Codespaces]**

Create `HeldBookingSchemaTest.java`:

```java
package com.tempoapi;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.util.TimeRangeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class HeldBookingSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private BookingRepository bookingRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void heldRowBlocksAnOverlappingConfirmedInsert() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d",
                java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);

        Booking held = new Booking(resource, user, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setHoldExpiresAt(Instant.now().plusSeconds(120));
        bookingRepository.saveAndFlush(held);

        Booking conflict = new Booking(resource, user, space,
                TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> bookingRepository.saveAndFlush(conflict))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findOverlappingCountsHeldRows() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d",
                java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 2", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);

        Booking held = new Booking(resource, user, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setHoldExpiresAt(Instant.now().plusSeconds(120));
        bookingRepository.saveAndFlush(held);

        assertThat(bookingRepository.findOverlapping(resource.getId(),
                TimeRangeUtil.toUtc(at(9)), TimeRangeUtil.toUtc(at(10)))).isNotEmpty();
    }
}
```

- [ ] **Step 7: Verify locally then commit**

Run: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"; mvn -o -q test-compile`
Expected: BUILD SUCCESS, no warnings. (HeldBookingSchemaTest runs in Codespaces.)

```bash
git add src/main/java/com/tempoapi/entity/BookingStatus.java \
        src/main/java/com/tempoapi/entity/Booking.java \
        src/main/resources/db/migration/V7__bookings_add_hold.sql \
        src/main/java/com/tempoapi/repository/BookingRepository.java \
        src/main/java/com/tempoapi/modules/availability/AvailabilityService.java \
        src/test/java/com/tempoapi/modules/availability/AvailabilityCacheTest.java \
        src/test/java/com/tempoapi/HeldBookingSchemaTest.java
git commit -m "feat: add HELD/EXPIRED booking states and hold expiry"
```

---

## Task 2: Waitlist entity, enum, table (V8), and repository

**Files:**
- Create: `src/main/java/com/tempoapi/entity/WaitlistStatus.java`
- Create: `src/main/java/com/tempoapi/entity/WaitlistEntry.java`
- Create: `src/main/resources/db/migration/V8__create_waitlist_entries.sql`
- Create: `src/main/java/com/tempoapi/repository/WaitlistEntryRepository.java`
- Test: `src/test/java/com/tempoapi/repository/WaitlistEntryRepositoryTest.java`

**Interfaces:**
- Produces: `WaitlistEntry` (constructor `(Resource, Space, User, Range<ZonedDateTime>, WaitlistStatus)`, getters/setters incl. `getHeldBooking`/`setHeldBooking`, `getStatus`/`setStatus`, `getCreatedAt`, `getTimeRange`); `WaitlistStatus.{WAITING,OFFERED,FULFILLED,EXPIRED,CANCELLED}`; `WaitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc`, `findByUserId(UUID, Pageable)`, `findByHeldBooking_Id(UUID)`, `existsActiveEntry(UUID, UUID, OffsetDateTime, OffsetDateTime)`.

- [ ] **Step 1: Create the status enum**

```java
package com.tempoapi.entity;

public enum WaitlistStatus {
    WAITING,
    OFFERED,
    FULFILLED,
    EXPIRED,
    CANCELLED
}
```

- [ ] **Step 2: Create the entity**

`WaitlistEntry.java`:

```java
package com.tempoapi.entity;

import io.hypersistence.utils.hibernate.type.range.PostgreSQLRangeType;
import io.hypersistence.utils.hibernate.type.range.Range;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Type(PostgreSQLRangeType.class)
    @Column(name = "time_range", nullable = false, columnDefinition = "tstzrange")
    private Range<ZonedDateTime> timeRange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_booking_id")
    private Booking heldBooking;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WaitlistEntry() {
    }

    public WaitlistEntry(Resource resource, Space space, User user,
                         Range<ZonedDateTime> timeRange, WaitlistStatus status) {
        this.resource = resource;
        this.space = space;
        this.user = user;
        this.timeRange = timeRange;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public Resource getResource() {
        return resource;
    }

    public Space getSpace() {
        return space;
    }

    public User getUser() {
        return user;
    }

    public Range<ZonedDateTime> getTimeRange() {
        return timeRange;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public void setStatus(WaitlistStatus status) {
        this.status = status;
    }

    public Booking getHeldBooking() {
        return heldBooking;
    }

    public void setHeldBooking(Booking heldBooking) {
        this.heldBooking = heldBooking;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

- [ ] **Step 3: Write the V8 migration**

`V8__create_waitlist_entries.sql`:

```sql
CREATE TABLE waitlist_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id     UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    space_id        UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    time_range      TSTZRANGE NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                    CHECK (status IN ('WAITING', 'OFFERED', 'FULFILLED', 'EXPIRED', 'CANCELLED')),
    held_booking_id UUID REFERENCES bookings (id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- A member may hold only one active position for the same resource and slot.
CREATE UNIQUE INDEX uq_waitlist_active_entry
    ON waitlist_entries (resource_id, user_id, time_range)
    WHERE status IN ('WAITING', 'OFFERED');

-- FIFO promotion lookup: oldest WAITING entry on a resource first.
CREATE INDEX idx_waitlist_resource_waiting
    ON waitlist_entries (resource_id, created_at) WHERE status = 'WAITING';

CREATE INDEX idx_waitlist_user ON waitlist_entries (user_id);
```

- [ ] **Step 4: Create the repository**

`WaitlistEntryRepository.java`:

```java
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
```

- [ ] **Step 5: Write the repository test [Codespaces]**

`WaitlistEntryRepositoryTest.java`:

```java
package com.tempoapi.repository;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.util.TimeRangeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WaitlistEntryRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void savesAndDetectsActiveEntry() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);

        waitlistEntryRepository.save(new WaitlistEntry(resource, space, user,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.WAITING));

        assertThat(waitlistEntryRepository.existsActiveEntry(resource.getId(), user.getId(),
                TimeRangeUtil.toUtc(at(9)), TimeRangeUtil.toUtc(at(10)))).isTrue();
        assertThat(waitlistEntryRepository
                .findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .hasSize(1);
    }
}
```

- [ ] **Step 6: Verify and commit**

Run: `mvn -o -q test-compile`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/tempoapi/entity/WaitlistStatus.java \
        src/main/java/com/tempoapi/entity/WaitlistEntry.java \
        src/main/resources/db/migration/V8__create_waitlist_entries.sql \
        src/main/java/com/tempoapi/repository/WaitlistEntryRepository.java \
        src/test/java/com/tempoapi/repository/WaitlistEntryRepositoryTest.java
git commit -m "feat: add waitlist_entries table, entity, and repository"
```

---

## Task 3: Domain exceptions and HTTP status mappings

**Files:**
- Create: `src/main/java/com/tempoapi/exception/OfferExpiredException.java`
- Create: `src/main/java/com/tempoapi/exception/AlreadyWaitlistedException.java`
- Create: `src/main/java/com/tempoapi/exception/SlotAvailableException.java`
- Modify: `src/main/java/com/tempoapi/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/tempoapi/exception/WaitlistExceptionHandlingTest.java`

**Interfaces:**
- Produces: three `RuntimeException` subclasses; handler mappings `OfferExpiredException -> 410`, `AlreadyWaitlistedException -> 409`, `SlotAvailableException -> 400`.

- [ ] **Step 1: Create the three exceptions**

Each mirrors `SlotUnavailableException`. `OfferExpiredException.java`:

```java
package com.tempoapi.exception;

public class OfferExpiredException extends RuntimeException {

    public OfferExpiredException(String message) {
        super(message);
    }
}
```

`AlreadyWaitlistedException.java` and `SlotAvailableException.java` are identical with their own class names.

- [ ] **Step 2: Write the failing handler test [local]**

`WaitlistExceptionHandlingTest.java`:

```java
package com.tempoapi.exception;

import com.tempoapi.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistExceptionHandlingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void expiredOfferMapsTo410() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleOfferExpired(new OfferExpiredException("gone"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void alreadyWaitlistedMapsTo409() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAlreadyWaitlisted(new AlreadyWaitlistedException("dup"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void slotAvailableMapsTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleSlotAvailable(new SlotAvailableException("book it"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 3: Run the test to confirm it fails**

Run: `mvn -o -q -Dtest=WaitlistExceptionHandlingTest test`
Expected: FAIL — `handleOfferExpired` etc. do not exist (compile error).

- [ ] **Step 4: Add the handler methods**

In `GlobalExceptionHandler.java`, add imports for the three exceptions and insert these handlers before `handleDataIntegrity`:

```java
    @ExceptionHandler(OfferExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleOfferExpired(OfferExpiredException ex) {
        return build(HttpStatus.GONE, ex.getMessage());
    }

    @ExceptionHandler(AlreadyWaitlistedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyWaitlisted(AlreadyWaitlistedException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(SlotAvailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleSlotAvailable(SlotAvailableException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `mvn -o -q -Dtest=WaitlistExceptionHandlingTest test`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tempoapi/exception/OfferExpiredException.java \
        src/main/java/com/tempoapi/exception/AlreadyWaitlistedException.java \
        src/main/java/com/tempoapi/exception/SlotAvailableException.java \
        src/main/java/com/tempoapi/exception/GlobalExceptionHandler.java \
        src/test/java/com/tempoapi/exception/WaitlistExceptionHandlingTest.java
git commit -m "feat: map waitlist domain exceptions to 410/409/400"
```

---

## Task 4: Realtime plumbing — user-targeted waitlist publisher

**Files:**
- Create: `src/main/java/com/tempoapi/websocket/WaitlistState.java`
- Create: `src/main/java/com/tempoapi/websocket/WaitlistMessage.java`
- Create: `src/main/java/com/tempoapi/websocket/WaitlistEventPublisher.java`
- Create: `src/main/java/com/tempoapi/websocket/LoggingWaitlistPublisher.java`
- Create: `src/main/java/com/tempoapi/websocket/WebSocketWaitlistPublisher.java`
- Modify: `src/main/java/com/tempoapi/config/WebSocketConfig.java:35`
- Test: `src/test/java/com/tempoapi/websocket/WebSocketWaitlistPublisherTest.java`

**Interfaces:**
- Produces: `WaitlistEventPublisher.publishOffer(UUID, WaitlistMessage)`, `publishGranted(UUID, WaitlistMessage)`; `WaitlistMessage` record; `WaitlistState.{OFFERED,GRANTED,EXPIRED}`.

- [ ] **Step 1: Create the enum and message**

`WaitlistState.java`:

```java
package com.tempoapi.websocket;

public enum WaitlistState {
    OFFERED,
    GRANTED,
    EXPIRED
}
```

`WaitlistMessage.java`:

```java
package com.tempoapi.websocket;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Private payload pushed to /user/queue/waitlist for one member: an offer to claim a
 * freed slot (with its expiry), or confirmation that a waitlisted slot was granted.
 */
public record WaitlistMessage(
        UUID entryId,
        UUID resourceId,
        String resourceName,
        UUID spaceId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Instant expiresAt,
        WaitlistState state) {
}
```

- [ ] **Step 2: Create the interface and logging implementation**

`WaitlistEventPublisher.java`:

```java
package com.tempoapi.websocket;

import java.util.UUID;

/**
 * Publishes private waitlist events to a single member. The waitlist service depends on
 * this interface, never on a concrete transport, mirroring {@link AvailabilityEventPublisher}.
 */
public interface WaitlistEventPublisher {

    /** Notifies a member that a freed slot is being offered to them. */
    void publishOffer(UUID userId, WaitlistMessage message);

    /** Notifies a member that their waitlisted slot is now confirmed. */
    void publishGranted(UUID userId, WaitlistMessage message);
}
```

`LoggingWaitlistPublisher.java`:

```java
package com.tempoapi.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Fallback publisher; the WebSocket implementation supersedes it via {@code @Primary}. */
@Component
public class LoggingWaitlistPublisher implements WaitlistEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingWaitlistPublisher.class);

    @Override
    public void publishOffer(UUID userId, WaitlistMessage message) {
        log.info("Waitlist OFFER user={} resource={} {}..{} expires={}",
                userId, message.resourceId(), message.startTime(), message.endTime(), message.expiresAt());
    }

    @Override
    public void publishGranted(UUID userId, WaitlistMessage message) {
        log.info("Waitlist GRANTED user={} resource={} {}..{}",
                userId, message.resourceId(), message.startTime(), message.endTime());
    }
}
```

- [ ] **Step 3: Write the failing WebSocket publisher test [local]**

`WebSocketWaitlistPublisherTest.java`:

```java
package com.tempoapi.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketWaitlistPublisherTest {

    @Test
    void offerGoesToTheUsersPrivateQueue() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        WebSocketWaitlistPublisher publisher = new WebSocketWaitlistPublisher(template);
        UUID userId = UUID.randomUUID();
        WaitlistMessage message = new WaitlistMessage(UUID.randomUUID(), UUID.randomUUID(), "Desk 1",
                UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now().plusHours(1),
                Instant.now().plusSeconds(120), WaitlistState.OFFERED);

        publisher.publishOffer(userId, message);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSendToUser(user.capture(), dest.capture(), org.mockito.ArgumentMatchers.eq(message));
        assertThat(user.getValue()).isEqualTo(userId.toString());
        assertThat(dest.getValue()).isEqualTo("/queue/waitlist");
    }
}
```

- [ ] **Step 4: Run the test to confirm it fails**

Run: `mvn -o -q -Dtest=WebSocketWaitlistPublisherTest test`
Expected: FAIL — `WebSocketWaitlistPublisher` does not exist.

- [ ] **Step 5: Create the WebSocket implementation**

`WebSocketWaitlistPublisher.java`:

```java
package com.tempoapi.websocket;

import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pushes private waitlist events to {@code /user/queue/waitlist}. Spring resolves the
 * user destination from the STOMP session principal (the user id set by the CONNECT
 * interceptor), so each member only receives their own offers.
 */
@Component
@Primary
public class WebSocketWaitlistPublisher implements WaitlistEventPublisher {

    private static final String DESTINATION = "/queue/waitlist";

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketWaitlistPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishOffer(UUID userId, WaitlistMessage message) {
        messagingTemplate.convertAndSendToUser(userId.toString(), DESTINATION, message);
    }

    @Override
    public void publishGranted(UUID userId, WaitlistMessage message) {
        messagingTemplate.convertAndSendToUser(userId.toString(), DESTINATION, message);
    }
}
```

- [ ] **Step 6: Enable the `/queue` broker destination**

In `WebSocketConfig.java:35`, change:

```java
        registry.enableSimpleBroker("/topic");
```

to:

```java
        registry.enableSimpleBroker("/topic", "/queue");
```

(The default `/user` destination prefix is already active; this lets the broker route the
resolved user-queue messages.)

- [ ] **Step 7: Run the test to confirm it passes**

Run: `mvn -o -q -Dtest=WebSocketWaitlistPublisherTest test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tempoapi/websocket/WaitlistState.java \
        src/main/java/com/tempoapi/websocket/WaitlistMessage.java \
        src/main/java/com/tempoapi/websocket/WaitlistEventPublisher.java \
        src/main/java/com/tempoapi/websocket/LoggingWaitlistPublisher.java \
        src/main/java/com/tempoapi/websocket/WebSocketWaitlistPublisher.java \
        src/main/java/com/tempoapi/config/WebSocketConfig.java \
        src/test/java/com/tempoapi/websocket/WebSocketWaitlistPublisherTest.java
git commit -m "feat: add user-targeted waitlist realtime publisher"
```

---

## Task 5: WaitlistService — join, list, leave (queue basics)

**Files:**
- Create: `src/main/java/com/tempoapi/modules/waitlist/dto/JoinWaitlistRequest.java`
- Create: `src/main/java/com/tempoapi/modules/waitlist/dto/WaitlistEntryResponse.java`
- Create: `src/main/java/com/tempoapi/modules/waitlist/WaitlistPromoter.java`
- Create: `src/main/java/com/tempoapi/modules/waitlist/WaitlistService.java`
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistServiceTest.java`

**Interfaces:**
- Consumes: `ResourceRepository.findByIdForUpdate` (only in Task 6+; here `findById`), `BookingRepository.findOverlapping`, `WaitlistEntryRepository.*`, `UserRepository.getReferenceById`, `TimeRangeUtil`.
- Produces: `WaitlistService.join(UUID, JoinWaitlistRequest) -> WaitlistEntryResponse`, `listMine(UUID, Pageable) -> PageResponse<WaitlistEntryResponse>`, `leaveOrDecline(UUID, UUID) -> void`; `WaitlistPromoter.promoteForReleasedSlot(SlotChange)`; `JoinWaitlistRequest(resourceId, startTime, endTime)`; `WaitlistEntryResponse(...)`.

> This task implements `join`, `listMine`, and the WAITING branch of `leaveOrDecline`. The
> `promoteForReleasedSlot` body, the OFFERED branch of `leaveOrDecline`, `claim`, and
> `expireHold` are added in Task 6. To keep this task compiling, give those methods minimal
> stubs as noted, then fill them in Task 6.

- [ ] **Step 1: Create the request DTO**

`JoinWaitlistRequest.java` (mirrors `CreateBookingRequest`):

```java
package com.tempoapi.modules.waitlist.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JoinWaitlistRequest(
        @NotNull UUID resourceId,
        @NotNull @Future OffsetDateTime startTime,
        @NotNull @Future OffsetDateTime endTime) {
}
```

- [ ] **Step 2: Create the response DTO**

`WaitlistEntryResponse.java`:

```java
package com.tempoapi.modules.waitlist.dto;

import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.util.TimeRangeUtil;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WaitlistEntryResponse(
        UUID id,
        UUID resourceId,
        String resourceName,
        UUID spaceId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        WaitlistStatus status,
        Integer position,
        Instant offerExpiresAt,
        Instant createdAt) {

    public static WaitlistEntryResponse from(WaitlistEntry entry, Integer position) {
        Instant offerExpiresAt = entry.getHeldBooking() == null
                ? null : entry.getHeldBooking().getHoldExpiresAt();
        return new WaitlistEntryResponse(
                entry.getId(),
                entry.getResource().getId(),
                entry.getResource().getName(),
                entry.getSpace().getId(),
                TimeRangeUtil.lower(entry.getTimeRange()),
                TimeRangeUtil.upper(entry.getTimeRange()),
                entry.getStatus(),
                position,
                offerExpiresAt,
                entry.getCreatedAt());
    }
}
```

- [ ] **Step 3: Create the promoter interface**

`WaitlistPromoter.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.websocket.SlotChange;

/**
 * Entry point the booking service calls after a slot is released, without depending on the
 * concrete waitlist service. The implementation either offers the slot to the next eligible
 * waiter (creating a hold) or publishes it as available.
 */
public interface WaitlistPromoter {

    void promoteForReleasedSlot(SlotChange releasedSlot);
}
```

- [ ] **Step 4: Write the failing service test [local]**

`WaitlistServiceTest.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.SlotAvailableException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.WaitlistEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock private WaitlistEntryRepository waitlistEntryRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ResourceRepository resourceRepository;
    @Mock private UserRepository userRepository;
    @Mock private AvailabilityCache availabilityCache;
    @Mock private AvailabilityEventPublisher availabilityEventPublisher;
    @Mock private WaitlistEventPublisher waitlistEventPublisher;

    private WaitlistService service() {
        return new WaitlistService(waitlistEntryRepository, bookingRepository, resourceRepository,
                userRepository, availabilityCache, availabilityEventPublisher, waitlistEventPublisher, 120);
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private Space space() {
        Space s = new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0));
        s.setId(UUID.randomUUID());
        return s;
    }

    private Resource resource(Space space) {
        Resource r = new Resource(space, "Desk 1", ResourceType.DESK, null);
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    void rejectsJoiningWhenSlotIsActuallyFree() {
        Space space = space();
        Resource resource = resource(space);
        when(resourceRepository.findById(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any())).thenReturn(List.of());

        JoinWaitlistRequest request = new JoinWaitlistRequest(resource.getId(), at(9), at(10));

        assertThatThrownBy(() -> service().join(UUID.randomUUID(), request))
                .isInstanceOf(SlotAvailableException.class);
    }

    @Test
    void joinsWhenSlotIsTaken() {
        Space space = space();
        Resource resource = resource(space);
        UUID userId = UUID.randomUUID();
        User user = new User("e@tempo.africa", "h", "F", "L", Role.MEMBER);
        user.setId(userId);

        when(resourceRepository.findById(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any()))
                .thenReturn(List.of(new com.tempoapi.entity.Booking()));
        when(waitlistEntryRepository.existsActiveEntry(any(), any(), any(), any())).thenReturn(false);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(waitlistEntryRepository.save(any(WaitlistEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(waitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .thenReturn(List.of());

        WaitlistEntryResponse response = service().join(userId, new JoinWaitlistRequest(resource.getId(), at(9), at(10)));

        assertThat(response.status()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(response.position()).isEqualTo(1);
    }
}
```

- [ ] **Step 5: Run the test to confirm it fails**

Run: `mvn -o -q -Dtest=WaitlistServiceTest test`
Expected: FAIL — `WaitlistService` does not exist.

- [ ] **Step 6: Create the service with join/list/leave + stubs**

`WaitlistService.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.ForbiddenException;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.SlotAvailableException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.SlotChange;
import com.tempoapi.websocket.WaitlistEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WaitlistService implements WaitlistPromoter {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final AvailabilityCache availabilityCache;
    private final AvailabilityEventPublisher availabilityEventPublisher;
    private final WaitlistEventPublisher waitlistEventPublisher;
    private final long holdSeconds;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                           BookingRepository bookingRepository,
                           ResourceRepository resourceRepository,
                           UserRepository userRepository,
                           AvailabilityCache availabilityCache,
                           AvailabilityEventPublisher availabilityEventPublisher,
                           WaitlistEventPublisher waitlistEventPublisher,
                           @Value("${app.waitlist.hold-seconds:120}") long holdSeconds) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.userRepository = userRepository;
        this.availabilityCache = availabilityCache;
        this.availabilityEventPublisher = availabilityEventPublisher;
        this.waitlistEventPublisher = waitlistEventPublisher;
        this.holdSeconds = holdSeconds;
    }

    /** Joins the FIFO waitlist for a currently-taken resource and slot. */
    @Transactional
    public WaitlistEntryResponse join(UUID userId, JoinWaitlistRequest request) {
        validateTimes(request.startTime(), request.endTime());

        Resource resource = resourceRepository.findById(request.resourceId())
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        if (!resource.isActive() || !resource.getSpace().isActive()) {
            throw new ResourceNotFoundException("Resource is not available");
        }
        Space space = resource.getSpace();
        validateWithinOpeningHours(space, request.startTime(), request.endTime());

        OffsetDateTime start = TimeRangeUtil.toUtc(request.startTime());
        OffsetDateTime end = TimeRangeUtil.toUtc(request.endTime());

        if (bookingRepository.findOverlapping(resource.getId(), start, end).isEmpty()) {
            throw new SlotAvailableException("The slot is available; book it directly instead of waitlisting");
        }
        if (waitlistEntryRepository.existsActiveEntry(resource.getId(), userId, start, end)) {
            throw new com.tempoapi.exception.AlreadyWaitlistedException("Already on the waitlist for this slot");
        }

        User user = userRepository.getReferenceById(userId);
        WaitlistEntry entry = new WaitlistEntry(resource, space, user,
                TimeRangeUtil.toRange(request.startTime(), request.endTime()), WaitlistStatus.WAITING);
        try {
            entry = waitlistEntryRepository.save(entry);
        } catch (DataIntegrityViolationException ex) {
            throw new com.tempoapi.exception.AlreadyWaitlistedException("Already on the waitlist for this slot");
        }
        return WaitlistEntryResponse.from(entry, positionOf(entry));
    }

    /** Lists the caller's waitlist entries. */
    @Transactional(readOnly = true)
    public PageResponse<WaitlistEntryResponse> listMine(UUID userId, Pageable pageable) {
        return PageResponse.from(waitlistEntryRepository.findByUserId(userId, pageable)
                .map(entry -> WaitlistEntryResponse.from(entry, null)));
    }

    /** Leaves a WAITING line, or declines an OFFERED hold (cascading to the next waiter). */
    @Transactional
    public void leaveOrDecline(UUID userId, UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        if (!entry.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only manage your own waitlist entry");
        }
        if (entry.getStatus() == WaitlistStatus.WAITING) {
            entry.setStatus(WaitlistStatus.CANCELLED);
            return;
        }
        if (entry.getStatus() == WaitlistStatus.OFFERED) {
            declineOffer(entry);
            return;
        }
        throw new ValidationException("Waitlist entry is not active");
    }

    // ---- Filled in Task 6 ----

    @Override
    @Transactional
    public void promoteForReleasedSlot(SlotChange releasedSlot) {
        // Implemented in Task 6.
    }

    private void declineOffer(WaitlistEntry entry) {
        // Implemented in Task 6.
    }

    private int positionOf(WaitlistEntry entry) {
        List<WaitlistEntry> waiting = waitlistEntryRepository
                .findByResourceIdAndStatusOrderByCreatedAtAsc(entry.getResource().getId(), WaitlistStatus.WAITING);
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId() != null && waiting.get(i).getId().equals(entry.getId())) {
                return i + 1;
            }
        }
        return waiting.size() + 1;
    }

    private void validateTimes(OffsetDateTime startTime, OffsetDateTime endTime) {
        if (!startTime.isBefore(endTime)) {
            throw new ValidationException("startTime must be before endTime");
        }
        if (!startTime.isAfter(OffsetDateTime.now())) {
            throw new ValidationException("startTime must be in the future");
        }
    }

    private void validateWithinOpeningHours(Space space, OffsetDateTime startTime, OffsetDateTime endTime) {
        if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
            throw new ValidationException("A booking must start and end on the same day");
        }
        if (startTime.toLocalTime().isBefore(space.getOpeningTime())) {
            throw new ValidationException("Slot starts before the space opening time");
        }
        if (endTime.toLocalTime().isAfter(space.getClosingTime())) {
            throw new ValidationException("Slot ends after the space closing time");
        }
    }
}
```

- [ ] **Step 7: Run the test to confirm it passes**

Run: `mvn -o -q -Dtest=WaitlistServiceTest test`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tempoapi/modules/waitlist/ \
        src/test/java/com/tempoapi/modules/waitlist/WaitlistServiceTest.java
git commit -m "feat: add waitlist join, list, and leave"
```

---

## Task 6: Promotion, claim, decline, and expiry (the concurrency heart)

**Files:**
- Modify: `src/main/java/com/tempoapi/modules/waitlist/WaitlistService.java`
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistPromotionTest.java`

**Interfaces:**
- Consumes: `ResourceRepository.findByIdForUpdate`, `BookingRepository.{findOverlapping,saveAndFlush,findByIdForUpdate}`, `WaitlistEntryRepository.{findByResourceIdAndStatusOrderByCreatedAtAsc,findByHeldBooking_Id}`, `WaitlistEventPublisher`, `AvailabilityEventPublisher`, `AvailabilityCache`.
- Produces: real bodies for `promoteForReleasedSlot(SlotChange)` and `declineOffer(WaitlistEntry)`; new public `claim(UUID, UUID) -> BookingResponse` and `expireHold(UUID) -> void`.

- [ ] **Step 1: Write the failing promotion test [local]**

`WaitlistPromotionTest.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.SlotChange;
import com.tempoapi.websocket.WaitlistEventPublisher;
import com.tempoapi.websocket.WaitlistMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistPromotionTest {

    @Mock private WaitlistEntryRepository waitlistEntryRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ResourceRepository resourceRepository;
    @Mock private UserRepository userRepository;
    @Mock private AvailabilityCache availabilityCache;
    @Mock private AvailabilityEventPublisher availabilityEventPublisher;
    @Mock private WaitlistEventPublisher waitlistEventPublisher;

    private WaitlistService service() {
        return new WaitlistService(waitlistEntryRepository, bookingRepository, resourceRepository,
                userRepository, availabilityCache, availabilityEventPublisher, waitlistEventPublisher, 120);
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private Space space() {
        Space s = new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0));
        s.setId(UUID.randomUUID());
        return s;
    }

    private Resource resource(Space space) {
        Resource r = new Resource(space, "Desk 1", ResourceType.DESK, null);
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    void promotionOffersHeldSlotToFirstWaiter() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.WAITING);

        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .thenReturn(List.of(entry));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any())).thenReturn(List.of());
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        SlotChange slot = new SlotChange(space.getId(), resource.getId(), "Desk 1", at(9), at(10));
        service().promoteForReleasedSlot(slot);

        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(entry.getHeldBooking()).isNotNull();
        assertThat(entry.getHeldBooking().getStatus()).isEqualTo(BookingStatus.HELD);
        verify(waitlistEventPublisher).publishOffer(eq(waiter.getId()), any(WaitlistMessage.class));
    }

    @Test
    void promotionWithNoWaitersPublishesAvailable() {
        Space space = space();
        Resource resource = resource(space);
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .thenReturn(List.of());

        SlotChange slot = new SlotChange(space.getId(), resource.getId(), "Desk 1", at(9), at(10));
        service().promoteForReleasedSlot(slot);

        verify(availabilityEventPublisher).publishAvailable(slot);
    }

    @Test
    void claimConfirmsLiveHold() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        Booking held = new Booking(resource, waiter, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setId(UUID.randomUUID());
        held.setHoldExpiresAt(Instant.now().plusSeconds(120));
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.OFFERED);
        entry.setHeldBooking(held);

        when(waitlistEntryRepository.findById(entry.getId() == null ? any() : entry.getId()))
                .thenReturn(Optional.of(entry));
        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));

        BookingResponse response = service().claim(waiter.getId(), entry.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(held.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.FULFILLED);
        verify(waitlistEventPublisher).publishGranted(eq(waiter.getId()), any(WaitlistMessage.class));
    }

    @Test
    void claimOnExpiredHoldIsRejected() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        Booking held = new Booking(resource, waiter, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setId(UUID.randomUUID());
        held.setHoldExpiresAt(Instant.now().minusSeconds(1));
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.OFFERED);
        entry.setHeldBooking(held);

        when(waitlistEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service().claim(waiter.getId(), entry.getId()))
                .isInstanceOf(OfferExpiredException.class);
    }
}
```

Note: the `findById(entry.getId())` stub requires the entry to have an id. In the two claim
tests, set it explicitly after construction with reflection-free access — add
`entry` ids by giving `WaitlistEntry` a package-private `setId` is not present, so instead use
Mockito `when(waitlistEntryRepository.findById(any())).thenReturn(Optional.of(entry))`. Replace
the `findById(entry.getId())` stubs with `findById(any())` to avoid needing an id setter.

- [ ] **Step 2: Run the test to confirm it fails**

Run: `mvn -o -q -Dtest=WaitlistPromotionTest test`
Expected: FAIL — `claim`/`expireHold` undefined and promotion is a stub.

- [ ] **Step 3: Implement promotion, claim, decline, expiry**

In `WaitlistService.java`, add imports:

```java
import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.websocket.WaitlistMessage;
import com.tempoapi.websocket.WaitlistState;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.time.LocalDate;
```

Replace the stub `promoteForReleasedSlot` and `declineOffer`, and add `claim`, `expireHold`,
`offerTo`, `expireHold`-helpers, plus the post-commit helper:

```java
    @Override
    @Transactional
    public void promoteForReleasedSlot(SlotChange releasedSlot) {
        Resource resource = resourceRepository.findByIdForUpdate(releasedSlot.resourceId())
                .orElse(null);
        if (resource == null) {
            return;
        }
        List<WaitlistEntry> waiting = waitlistEntryRepository
                .findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING);
        for (WaitlistEntry entry : waiting) {
            OffsetDateTime start = TimeRangeUtil.lower(entry.getTimeRange());
            OffsetDateTime end = TimeRangeUtil.upper(entry.getTimeRange());
            if (!bookingRepository.findOverlapping(resource.getId(), start, end).isEmpty()) {
                continue;
            }
            if (offerTo(entry, resource)) {
                return;
            }
        }
        // No eligible waiter: the slot is genuinely free.
        LocalDate date = releasedSlot.startTime().toLocalDate();
        publishAfterCommit(() -> {
            availabilityCache.evict(releasedSlot.spaceId(), date);
            availabilityEventPublisher.publishAvailable(releasedSlot);
        });
    }

    /** Creates a HELD booking for the entry and notifies the member. Returns false if the insert raced and lost. */
    private boolean offerTo(WaitlistEntry entry, Resource resource) {
        Booking hold = new Booking(resource, entry.getUser(), entry.getSpace(),
                entry.getTimeRange(), BookingStatus.HELD);
        hold.setHoldExpiresAt(Instant.now().plusSeconds(holdSeconds));
        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
        entry.setStatus(WaitlistStatus.OFFERED);
        entry.setHeldBooking(saved);

        UUID userId = entry.getUser().getId();
        WaitlistMessage message = messageFor(entry, saved.getHoldExpiresAt(), WaitlistState.OFFERED);
        LocalDate date = TimeRangeUtil.lower(entry.getTimeRange()).toLocalDate();
        publishAfterCommit(() -> {
            availabilityCache.evict(entry.getSpace().getId(), date);
            waitlistEventPublisher.publishOffer(userId, message);
        });
        return true;
    }

    /** Claims a live offer, turning the hold into a confirmed booking. */
    @Transactional
    public BookingResponse claim(UUID userId, UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        if (!entry.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only claim your own offer");
        }
        if (entry.getStatus() != WaitlistStatus.OFFERED || entry.getHeldBooking() == null) {
            throw new OfferExpiredException("No live offer to claim");
        }
        Booking hold = bookingRepository.findByIdForUpdate(entry.getHeldBooking().getId())
                .orElseThrow(() -> new OfferExpiredException("The offer has expired"));
        if (hold.getStatus() != BookingStatus.HELD
                || hold.getHoldExpiresAt() == null
                || hold.getHoldExpiresAt().isBefore(Instant.now())) {
            throw new OfferExpiredException("The offer has expired");
        }
        hold.setStatus(BookingStatus.CONFIRMED);
        hold.setHoldExpiresAt(null);
        entry.setStatus(WaitlistStatus.FULFILLED);

        BookingResponse response = BookingResponse.from(hold);
        WaitlistMessage message = messageFor(entry, null, WaitlistState.GRANTED);
        LocalDate date = response.startTime().toLocalDate();
        publishAfterCommit(() -> {
            availabilityCache.evict(response.spaceId(), date);
            availabilityEventPublisher.publishBooked(new SlotChange(
                    response.spaceId(), response.resourceId(), response.resourceName(),
                    response.startTime(), response.endTime()));
            waitlistEventPublisher.publishGranted(userId, message);
        });
        return response;
    }

    /** Expires one hold (called by the scheduler) and cascades to the next waiter. */
    @Transactional
    public void expireHold(UUID bookingId) {
        Booking hold = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (hold == null || hold.getStatus() != BookingStatus.HELD) {
            return;
        }
        if (hold.getHoldExpiresAt() == null || hold.getHoldExpiresAt().isAfter(Instant.now())) {
            return;
        }
        hold.setStatus(BookingStatus.EXPIRED);
        waitlistEntryRepository.findByHeldBooking_Id(bookingId)
                .ifPresent(entry -> entry.setStatus(WaitlistStatus.EXPIRED));
        promoteForReleasedSlot(new SlotChange(
                hold.getSpace().getId(), hold.getResource().getId(), hold.getResource().getName(),
                TimeRangeUtil.lower(hold.getTimeRange()), TimeRangeUtil.upper(hold.getTimeRange())));
    }

    private void declineOffer(WaitlistEntry entry) {
        Booking hold = entry.getHeldBooking();
        entry.setStatus(WaitlistStatus.CANCELLED);
        if (hold != null && hold.getStatus() == BookingStatus.HELD) {
            hold.setStatus(BookingStatus.EXPIRED);
            promoteForReleasedSlot(new SlotChange(
                    hold.getSpace().getId(), hold.getResource().getId(), hold.getResource().getName(),
                    TimeRangeUtil.lower(hold.getTimeRange()), TimeRangeUtil.upper(hold.getTimeRange())));
        }
    }

    private WaitlistMessage messageFor(WaitlistEntry entry, Instant expiresAt, WaitlistState state) {
        return new WaitlistMessage(
                entry.getId(),
                entry.getResource().getId(),
                entry.getResource().getName(),
                entry.getSpace().getId(),
                TimeRangeUtil.lower(entry.getTimeRange()),
                TimeRangeUtil.upper(entry.getTimeRange()),
                expiresAt,
                state);
    }

    private void publishAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
```

- [ ] **Step 4: Adjust the claim-test stubs**

Per the note in Step 1, in `WaitlistPromotionTest` change the two `findById(entry.getId())`
stubs to `findById(any())` so no entry-id setter is required:

```java
        when(waitlistEntryRepository.findById(any())).thenReturn(Optional.of(entry));
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `mvn -o -q -Dtest=WaitlistPromotionTest test`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tempoapi/modules/waitlist/WaitlistService.java \
        src/test/java/com/tempoapi/modules/waitlist/WaitlistPromotionTest.java
git commit -m "feat: add waitlist promotion, claim, decline, and hold expiry"
```

---

## Task 7: Wire cancellation into promotion

**Files:**
- Modify: `src/main/java/com/tempoapi/modules/bookings/BookingService.java`
- Modify: `src/test/java/com/tempoapi/modules/bookings/BookingServiceTest.java`

**Interfaces:**
- Consumes: `WaitlistPromoter.promoteForReleasedSlot(SlotChange)`.
- Produces: `cancelBooking` now delegates the post-release publish to `WaitlistPromoter`.

- [ ] **Step 1: Add the dependency and delegate on cancel**

In `BookingService.java`, add the field, constructor param, and import:

```java
import com.tempoapi.modules.waitlist.WaitlistPromoter;
```

Add `private final WaitlistPromoter waitlistPromoter;` and extend the constructor (append the
parameter and assignment).

Replace the tail of `cancelBooking` (the `BookingResponse response = ...` block through the
`publishAfterCommit(...)` call) with:

```java
        BookingResponse response = BookingResponse.from(booking);
        SlotChange slot = toSlot(response);
        publishAfterCommit(() -> waitlistPromoter.promoteForReleasedSlot(slot));
        return response;
```

(The unused `LocalDate affectedDate` line in cancel is removed; promotion now owns cache
eviction and the availability publish.)

- [ ] **Step 2: Update the unit test — add the mock and a delegation test**

In `BookingServiceTest.java`, add:

```java
import com.tempoapi.modules.waitlist.WaitlistPromoter;
```

Add the mock field alongside the others:

```java
    @Mock
    private WaitlistPromoter waitlistPromoter;
```

Add this test (mirrors the create test's setup but cancels):

```java
    @Test
    void cancelDelegatesToWaitlistPromoter() {
        Space space = activeSpace();
        Resource resource = activeResource(space);
        UUID userId = UUID.randomUUID();
        User user = new User("e@tempo.africa", "hash", "First", "Last", Role.MEMBER);
        user.setId(userId);

        OffsetDateTime start = tomorrowAt(9);
        OffsetDateTime end = tomorrowAt(10);
        Booking booking = new Booking(resource, user, space, TimeRangeUtil.toRange(start, end), BookingStatus.CONFIRMED);
        booking.setId(UUID.randomUUID());

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        bookingService.cancelBooking(userId, booking.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(waitlistPromoter).promoteForReleasedSlot(any(SlotChange.class));
    }
```

- [ ] **Step 3: Run the booking tests**

Run: `mvn -o -q -Dtest=BookingServiceTest test`
Expected: PASS (all prior tests plus the new one).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tempoapi/modules/bookings/BookingService.java \
        src/test/java/com/tempoapi/modules/bookings/BookingServiceTest.java
git commit -m "feat: route booking cancellation through waitlist promotion"
```

---

## Task 8: REST controller

**Files:**
- Create: `src/main/java/com/tempoapi/modules/waitlist/WaitlistController.java`
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistControllerTest.java`

**Interfaces:**
- Consumes: `WaitlistService.{join,listMine,leaveOrDecline,claim}`, `SecurityUtils.currentUserId()`.
- Produces: `POST /api/v1/waitlist`, `GET /api/v1/waitlist/mine`, `DELETE /api/v1/waitlist/{entryId}`, `POST /api/v1/waitlist/offers/{entryId}/claim`.

- [ ] **Step 1: Create the controller**

`WaitlistController.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.common.ApiResponse;
import com.tempoapi.common.PageResponse;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WaitlistEntryResponse>> join(@Valid @RequestBody JoinWaitlistRequest request) {
        WaitlistEntryResponse response = waitlistService.join(SecurityUtils.currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Added to waitlist", response));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<PageResponse<WaitlistEntryResponse>>> mine(
            @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<WaitlistEntryResponse> page = waitlistService.listMine(SecurityUtils.currentUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<ApiResponse<Void>> leave(@PathVariable UUID entryId) {
        waitlistService.leaveOrDecline(SecurityUtils.currentUserId(), entryId);
        return ResponseEntity.ok(ApiResponse.ok("Waitlist entry removed", null));
    }

    @PostMapping("/offers/{entryId}/claim")
    public ResponseEntity<ApiResponse<BookingResponse>> claim(@PathVariable UUID entryId) {
        BookingResponse response = waitlistService.claim(SecurityUtils.currentUserId(), entryId);
        return ResponseEntity.ok(ApiResponse.ok("Offer claimed", response));
    }
}
```

- [ ] **Step 2: Write the controller test [Codespaces]**

`WaitlistControllerTest.java` follows the existing `BookingControllerTest` pattern
(`@SpringBootTest`, `@AutoConfigureMockMvc`, seed a space/resource/booking, then exercise the
endpoints). Minimum cases: joining a taken slot returns 201; joining a free slot returns 400;
unauthenticated request returns 401.

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WaitlistControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SpaceRepository spaceRepository;
    @Autowired private ResourceRepository resourceRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void joiningAFreeSlotReturns400() throws Exception {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User member = persistUser(Role.MEMBER);

        String body = objectMapper.writeValueAsString(Map.of(
                "resourceId", resource.getId().toString(),
                "startTime", at(9).toString(),
                "endTime", at(10).toString()));

        mockMvc.perform(post("/api/v1/waitlist")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void joiningWithoutAuthReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 3: Verify and commit**

Run: `mvn -o -q test-compile`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/tempoapi/modules/waitlist/WaitlistController.java \
        src/test/java/com/tempoapi/modules/waitlist/WaitlistControllerTest.java
git commit -m "feat: add waitlist REST endpoints"
```

---

## Task 9: Scheduled hold expiry sweep

**Files:**
- Create: `src/main/java/com/tempoapi/modules/waitlist/WaitlistScheduler.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/tempoapi/config/SchedulingConfig.java`
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistSchedulerTest.java`

**Interfaces:**
- Consumes: `BookingRepository.findExpiredHolds(OffsetDateTime, Pageable)`, `WaitlistService.expireHold(UUID)`.
- Produces: `WaitlistScheduler.sweepExpiredHolds()` (also callable directly in tests).

- [ ] **Step 1: Add config properties**

In `application.properties`, under the Application section add:

```properties
app.waitlist.hold-seconds=${WAITLIST_HOLD_SECONDS:120}
app.waitlist.sweep-ms=${WAITLIST_SWEEP_MS:10000}
```

- [ ] **Step 2: Enable scheduling**

Create `SchedulingConfig.java`:

```java
package com.tempoapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring's scheduled-task support for the waitlist hold sweep. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 3: Write the failing scheduler test [local]**

`WaitlistSchedulerTest.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Booking;
import com.tempoapi.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistSchedulerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private WaitlistService waitlistService;

    @Test
    void sweepExpiresEachStaleHold() {
        Booking hold = new Booking();
        hold.setId(UUID.randomUUID());
        when(bookingRepository.findExpiredHolds(any(OffsetDateTime.class), any()))
                .thenReturn(List.of(hold));

        new WaitlistScheduler(bookingRepository, waitlistService).sweepExpiredHolds();

        verify(waitlistService).expireHold(hold.getId());
    }
}
```

- [ ] **Step 4: Run the test to confirm it fails**

Run: `mvn -o -q -Dtest=WaitlistSchedulerTest test`
Expected: FAIL — `WaitlistScheduler` does not exist.

- [ ] **Step 5: Create the scheduler**

`WaitlistScheduler.java`:

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Booking;
import com.tempoapi.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Periodically expires holds whose offer window has lapsed and cascades each freed slot to the
 * next waiter. Single-instance: with multiple app nodes, wrap the sweep in a distributed lock
 * (for example ShedLock) so only one node promotes.
 */
@Component
public class WaitlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitlistScheduler.class);
    private static final int BATCH = 100;

    private final BookingRepository bookingRepository;
    private final WaitlistService waitlistService;

    public WaitlistScheduler(BookingRepository bookingRepository, WaitlistService waitlistService) {
        this.bookingRepository = bookingRepository;
        this.waitlistService = waitlistService;
    }

    @Scheduled(fixedDelayString = "${app.waitlist.sweep-ms:10000}")
    public void sweepExpiredHolds() {
        List<Booking> expired = bookingRepository.findExpiredHolds(
                OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, BATCH));
        for (Booking hold : expired) {
            try {
                waitlistService.expireHold(hold.getId());
            } catch (RuntimeException ex) {
                log.warn("Failed to expire hold {}: {}", hold.getId(), ex.getMessage());
            }
        }
    }
}
```

- [ ] **Step 6: Run the test to confirm it passes**

Run: `mvn -o -q -Dtest=WaitlistSchedulerTest test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tempoapi/modules/waitlist/WaitlistScheduler.java \
        src/main/java/com/tempoapi/config/SchedulingConfig.java \
        src/main/resources/application.properties \
        src/test/java/com/tempoapi/modules/waitlist/WaitlistSchedulerTest.java
git commit -m "feat: add scheduled hold expiry and cascade sweep"
```

---

## Task 10: End-to-end concurrency and realtime integration tests

**Files:**
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistCascadeIntegrationTest.java`
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistClaimRaceTest.java`
- Test: `src/test/java/com/tempoapi/modules/waitlist/WaitlistWebSocketTest.java`

All **[Codespaces]** (Testcontainers). The local gate is `mvn -o -q test-compile`.

**Interfaces:**
- Consumes: `WaitlistService`, `BookingService`, `WaitlistScheduler`, repositories, `AbstractIntegrationTest` helpers.

- [ ] **Step 1: Cascade test**

`WaitlistCascadeIntegrationTest.java` — A books a desk; B then C join the waitlist; A cancels;
assert B is `OFFERED` with a `HELD` booking. B declines (`leaveOrDecline`); assert C becomes
`OFFERED`. C claims; assert exactly one `CONFIRMED` booking exists for the slot and C's entry is
`FULFILLED`.

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WaitlistCascadeIntegrationTest extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private WaitlistService waitlistService;
    @Autowired private SpaceRepository spaceRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private WaitlistEntryRepository waitlistEntryRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void cancellationOffersThenCascadesThenConfirms() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User a = persistUser(Role.MEMBER);
        User b = persistUser(Role.MEMBER);
        User c = persistUser(Role.MEMBER);

        BookingResponse booking = bookingService.createBooking(a.getId(),
                new CreateBookingRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(b.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(c.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));

        bookingService.cancelBooking(a.getId(), booking.id());

        WaitlistEntry bEntry = waitlistEntryRepository.findByUserId(b.getId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);
        assertThat(bEntry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(bEntry.getHeldBooking().getStatus()).isEqualTo(BookingStatus.HELD);

        waitlistService.leaveOrDecline(b.getId(), bEntry.getId());

        WaitlistEntry cEntry = waitlistEntryRepository.findByUserId(c.getId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);
        assertThat(cEntry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);

        BookingResponse confirmed = waitlistService.claim(c.getId(), cEntry.getId());
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);

        long confirmedCount = bookingRepository.countByResourceIdAndStatus(resource.getId(), BookingStatus.CONFIRMED);
        assertThat(confirmedCount).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Claim-vs-expiry race test**

`WaitlistClaimRaceTest.java` — create an offer with a hold window of 1 second (override
`app.waitlist.hold-seconds=1` via `@DynamicPropertySource` or `@TestPropertySource`); wait past
expiry; run `claim` and `WaitlistScheduler.sweepExpiredHolds()` and assert exactly one wins:
either claim succeeds and the sweep is a no-op, or the sweep expires it and claim throws
`OfferExpiredException`. A deterministic version: call `sweepExpiredHolds()` first, then assert
`claim` throws `OfferExpiredException`; and in a second case call `claim` first (before expiry)
and assert the later sweep leaves the now-`CONFIRMED` booking untouched.

```java
package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "app.waitlist.hold-seconds=1")
class WaitlistClaimRaceTest extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private WaitlistService waitlistService;
    @Autowired private WaitlistScheduler waitlistScheduler;
    @Autowired private SpaceRepository spaceRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private WaitlistEntryRepository waitlistEntryRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private WaitlistEntry offerToWaiter() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User a = persistUser(Role.MEMBER);
        User b = persistUser(Role.MEMBER);
        BookingResponse booking = bookingService.createBooking(a.getId(),
                new CreateBookingRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(b.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));
        bookingService.cancelBooking(a.getId(), booking.id());
        return waitlistEntryRepository.findByUserId(b.getId(), Pageable.unpaged()).getContent().get(0);
    }

    @Test
    void sweeperWinsAfterExpiryAndClaimIsRejected() throws InterruptedException {
        WaitlistEntry entry = offerToWaiter();
        Thread.sleep(1100);
        waitlistScheduler.sweepExpiredHolds();

        assertThatThrownBy(() -> waitlistService.claim(entry.getUser().getId(), entry.getId()))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    void claimBeforeExpiryWinsAndSweepIsNoOp() throws InterruptedException {
        WaitlistEntry entry = offerToWaiter();
        BookingResponse confirmed = waitlistService.claim(entry.getUser().getId(), entry.getId());
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);

        Thread.sleep(1100);
        waitlistScheduler.sweepExpiredHolds(); // must not touch the now-confirmed booking

        WaitlistEntry reloaded = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WaitlistStatus.FULFILLED);
    }
}
```

- [ ] **Step 3: WebSocket offer test**

`WaitlistWebSocketTest.java` follows the existing `WebSocketAvailabilityTest` pattern: connect a
STOMP client authenticated as B with the JWT on the CONNECT frame, subscribe to
`/user/queue/waitlist`, then (on the server side) book as A and cancel as A so B is promoted;
assert B receives a `WaitlistMessage` with `state = OFFERED`. Reuse that test's
`WebSocketStompClient` setup and `StompSessionHandlerAdapter`; the subscribe destination is the
only material difference, plus passing B's bearer token in the CONNECT headers so the user
destination resolves.

- [ ] **Step 4: Verify and commit**

Run: `mvn -o -q test-compile`
Expected: BUILD SUCCESS.

```bash
git add src/test/java/com/tempoapi/modules/waitlist/WaitlistCascadeIntegrationTest.java \
        src/test/java/com/tempoapi/modules/waitlist/WaitlistClaimRaceTest.java \
        src/test/java/com/tempoapi/modules/waitlist/WaitlistWebSocketTest.java
git commit -m "test: add waitlist cascade, claim-race, and websocket integration tests"
```

---

## Task 11: Documentation, Postman, deploy env (Sprint 6 close-out)

**Files:**
- Modify: `DEFENSE.md`
- Modify: `README.md`
- Modify: `docs/tempo-api.postman_collection.json`
- Modify: `docs/websocket-testing.md`
- Modify: `deploy/tempo.env.example`

**Interfaces:** none (docs only).

- [ ] **Step 1: DEFENSE.md — add the waitlist section**

Append a new section after the caching section covering, in interview-ready prose:
(a) the waitlist as a second exactly-one-winner problem; (b) the hold modeled as a `HELD`
booking row guarded by the same exclusion constraint (extended to `CONFIRMED|HELD`); (c) the
claim-vs-expiry race resolved by a pessimistic row lock on the held booking; (d) why a direct
booker cannot steal a held slot (`findOverlapping` counts `HELD`); (e) the scheduler, its
single-node assumption, and ShedLock as the multi-node fix; (f) the failure modes (missed sweep,
restart, claim-at-expiry, cascade storm).

- [ ] **Step 2: README.md — document the feature**

Add a "Waitlist and auto-promotion" subsection under the realtime/API material: the four
endpoints with request/response shapes, the offer lifecycle (`WAITING -> OFFERED -> FULFILLED`,
or `EXPIRED`/`CANCELLED`), the `/user/queue/waitlist` private channel, and the two new env vars.
First person, no emojis, no AI references.

- [ ] **Step 3: Postman — add a Waitlist folder**

Add a `Waitlist` folder to `docs/tempo-api.postman_collection.json` with requests: Join waitlist
(POST `/api/v1/waitlist`), My waitlist (GET `/api/v1/waitlist/mine`), Leave/decline (DELETE
`/api/v1/waitlist/{{entryId}}`), Claim offer (POST `/api/v1/waitlist/offers/{{entryId}}/claim`).
Reuse the `{{access_token}}` bearer variable. Validate the file remains well-formed JSON.

- [ ] **Step 4: websocket-testing.md — document the user queue**

Add a section showing how to subscribe to `/user/queue/waitlist` after CONNECT with the JWT and
what an offer message looks like.

- [ ] **Step 5: deploy/tempo.env.example — add the new vars**

Append:

```
WAITLIST_HOLD_SECONDS=120
WAITLIST_SWEEP_MS=10000
```

- [ ] **Step 6: Final verification and commit**

Run the full local unit subset and compile:

```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
mvn -o -q test-compile
mvn -o -q -Dtest=WaitlistServiceTest,WaitlistPromotionTest,WaitlistSchedulerTest,WebSocketWaitlistPublisherTest,WaitlistExceptionHandlingTest,BookingServiceTest test
```

Expected: BUILD SUCCESS; all listed unit tests pass. (Testcontainers tests run in Codespaces via
`mvn clean test`.)

Confirm no emojis or AI references were introduced (review the doc diffs).

```bash
git add DEFENSE.md README.md docs/tempo-api.postman_collection.json docs/websocket-testing.md deploy/tempo.env.example
git commit -m "docs: document waitlist auto-promotion, API, and deployment env"
```

---

## Final Sprint Verification (run in Codespaces)

- [ ] `mvn clean test` is green (unit + Testcontainers) with Docker available.
- [ ] Flyway applies V7 and V8 cleanly, including the widened exclusion constraint.
- [ ] Cascade, claim-race, hold-blocks-direct-booking, and WebSocket-offer tests pass.
- [ ] No compiler warnings.
- [ ] DEFENSE.md and README reflect the waitlist.
