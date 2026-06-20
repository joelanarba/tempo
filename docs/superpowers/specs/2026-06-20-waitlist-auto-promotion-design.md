# Design: Waitlist with Offer / Hold / Cascade Auto-Promotion

Date: 2026-06-20
Status: Approved for implementation
Scope: Sprint 6 of Tempo

## Summary

When a desk or room is fully booked for a time slot, a member can join a waitlist for that
specific resource and slot. When the confirming booking is cancelled (or a hold expires), the
freed slot is offered to the next member in line. The offered member receives a short, exclusive
hold and a realtime notification, and has a fixed window to claim it. If they ignore or decline
the offer, it cascades to the next member; if nobody claims it, the slot becomes publicly
available again.

This is deliberately a *second* "exactly one winner" concurrency problem, layered on the same two
mechanisms the booking core already uses: pessimistic row locking and the PostgreSQL GiST
exclusion constraint. The waitlist does not introduce a new concurrency model; it demonstrates
that the existing one generalizes to a harder problem.

## Goals

- A member can join, view, leave, and decline waitlist positions for a specific resource + slot.
- On cancellation of a confirming booking, the freed slot is offered FIFO to the next waiter.
- An offer is an exclusive, time-bounded hold. Exactly one of {claimed, expired} happens.
- A held slot cannot be stolen by a direct booking attempt.
- The offered member is notified in realtime over a private, user-targeted channel.
- Correctness is guaranteed at the database level, not by lucky timing.

## Non-goals

- Cross-resource waitlisting ("any desk in this space"). The waitlist is for a specific resource
  and a specific slot. This keeps promotion an exact-match problem and reuses the exclusion
  constraint directly.
- Multi-node scheduler coordination. The system assumes a single application instance (one EC2
  box). The multi-node fix (a distributed lock such as ShedLock around the sweep) is documented
  as a boundary, not implemented.
- Notifications outside the WebSocket channel (email, SMS, push).

## The central modeling decision

A hold is a real `bookings` row in a new `HELD` status, not an application-level flag and not a
Redis key. The exclusion constraint is extended from

```
EXCLUDE USING gist (resource_id WITH =, time_range WITH &&) WHERE (status = 'CONFIRMED')
```

to

```
EXCLUDE USING gist (resource_id WITH =, time_range WITH &&) WHERE (status IN ('CONFIRMED','HELD'))
```

Consequences:

- A held slot is guarded by the same GiST constraint as a confirmed slot. A direct booking attempt
  during a hold window is rejected at the database level.
- Claiming an offer is a status transition `HELD -> CONFIRMED` on a row that already holds the slot
  exclusively. No new row, no new overlap risk.
- Expiry is `HELD -> EXPIRED`, which releases the constraint.

The cost is that `bookings` now contains transient `HELD` and terminal `EXPIRED` rows. This is
neutralized two ways: a partial index on `(hold_expires_at) WHERE status = 'HELD'` keeps the
sweeper query fast, and every existing query already filters by status, so non-confirmed rows
never leak into confirmed-booking results.

## Booking lifecycle (state machine)

```
                        cancel
   CONFIRMED ─────────────────────────► CANCELLED
       ▲                                     │ frees slot -> promote
 claim │                                     ▼
     HELD ◄──────── promote (offer) ──── waitlist FIFO
       │                                     ▲
 expire│ (sweeper) / decline                 │ cascade to next
       ▼                                     │
    EXPIRED ─────────────────────────────────┘
```

Waitlist entry states: `WAITING -> OFFERED -> FULFILLED`, plus terminal `EXPIRED` and `CANCELLED`.

## Schema changes

### V7 — alter `bookings`

- Drop the existing status `CHECK`; recreate as
  `CHECK (status IN ('CONFIRMED','CANCELLED','HELD','EXPIRED'))`. All names fit `VARCHAR(10)`.
- Drop `bookings_no_overlap`; recreate with `WHERE (status IN ('CONFIRMED','HELD'))`.
- Add column `hold_expires_at TIMESTAMPTZ NULL`.
- Add `CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL)` — a hold must have an expiry.
- Add partial index `idx_bookings_active_holds ON bookings (hold_expires_at) WHERE status = 'HELD'`.

### V8 — create `waitlist_entries`

```
id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
resource_id     UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE
space_id        UUID NOT NULL REFERENCES spaces (id) ON DELETE CASCADE
user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE
time_range      TSTZRANGE NOT NULL
status          VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                CHECK (status IN ('WAITING','OFFERED','FULFILLED','EXPIRED','CANCELLED'))
held_booking_id UUID NULL REFERENCES bookings (id) ON DELETE SET NULL
created_at      TIMESTAMPTZ NOT NULL DEFAULT now()   -- FIFO ordering key
updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
```

Indexes:

- `UNIQUE (resource_id, user_id, time_range) WHERE status IN ('WAITING','OFFERED')` — a member
  cannot hold two active positions for the same resource and slot.
- `idx_waitlist_resource_active ON waitlist_entries (resource_id, created_at) WHERE status = 'WAITING'`
  — backs FIFO promotion lookup.
- `idx_waitlist_user ON waitlist_entries (user_id)` — backs "my waitlist".

### Repository query changes

- `findOverlapping` must count `status IN ('CONFIRMED','HELD')`, so an active hold blocks direct
  booking attempts before the constraint backstop is even reached.
- The availability snapshot query (`findConfirmedInSpaceForWindow`) must count `HELD` as occupied,
  so cached availability never shows a held slot as free.

## The two races

### Race 1 — claim vs. expiry

The claim endpoint and the scheduler sweeper both target the same `HELD` booking row. Each acquires
a pessimistic row lock (`SELECT ... FOR UPDATE`) on that booking before mutating it.

- If claim locks first: `HELD -> CONFIRMED`. The sweeper later reads the row, sees status is no
  longer `HELD`, and skips it.
- If the sweeper locks first: `HELD -> EXPIRED` plus cascade. The claim then reads the row, sees it
  is no longer a live hold (status changed or `hold_expires_at` passed), and fails with
  `OfferExpiredException` (HTTP 410 Gone).

Exactly one outcome. No double confirmation, no lost cascade.

### Race 2 — claim vs. a direct booker

A member trying to directly book the held resource and slot runs `createBooking`, which locks the
resource row and runs `findOverlapping`. Because `findOverlapping` now counts `HELD`, the direct
booker sees the slot occupied and receives `SlotUnavailableException`. The exclusion constraint is
the backstop if logic is bypassed. The held slot cannot be stolen.

## Promotion algorithm

`promoteWaitlist(resourceId, freedRange)` runs in its own transaction and reuses the createBooking
shape exactly:

1. Acquire the pessimistic lock on the resource row.
2. Find the FIFO-first `WAITING` entry on that resource whose `time_range` no longer overlaps any
   `CONFIRMED` or `HELD` booking (`findOverlapping` returns empty for that range).
3. If found: insert a `HELD` booking for that member with
   `hold_expires_at = now() + WAITLIST_HOLD_SECONDS`; set the entry to `OFFERED` and link
   `held_booking_id`. Post-commit: notify that member on `/user/queue/waitlist` and evict the
   availability cache (the slot stays unavailable to the public).
4. If no eligible entry exists: the slot is genuinely free. Post-commit: publish an AVAILABLE
   update to the space topic and evict the cache.

Promotion is triggered post-commit from: a cancellation, an offer decline, and a sweeper expiry.

## API surface

All endpoints return `ResponseEntity<ApiResponse<T>>`. List endpoints are paginated with
`PageResponse<T>`.

| Method | Path                                       | Auth   | Purpose |
|--------|--------------------------------------------|--------|---------|
| POST   | `/api/v1/waitlist`                         | MEMBER | Join a line for a currently-taken resource + slot. Body `{resourceId, startTime, endTime}`. Returns `{entryId, position, status}`. |
| GET    | `/api/v1/waitlist/mine`                    | MEMBER | List the caller's entries, including any live offer with `expiresAt`. Optional `status` filter. |
| DELETE | `/api/v1/waitlist/{entryId}`               | MEMBER | Leave (if `WAITING`) or decline (if `OFFERED`, triggering immediate cascade). |
| POST   | `/api/v1/waitlist/offers/{entryId}/claim`  | MEMBER | Claim a live offer. Transitions `HELD -> CONFIRMED`, returns `BookingResponse`. |

### Validation rules

- Join is rejected with `SlotAvailableException` (400) if the requested slot is currently bookable
  (`findOverlapping` empty) — the member should book directly.
- Join reuses the booking-time rules: start before end, start in the future, same calendar day,
  within the space opening hours.
- Join is rejected with `AlreadyWaitlistedException` (409) if the member already has an active
  entry for that resource and slot (enforced by the unique partial index, surfaced cleanly).
- Claim is rejected with `OfferExpiredException` (410) if the offer is no longer live.
- Leave / decline / claim require the caller to own the entry, else `ForbiddenException` (403).

## Realtime design

A new user-targeted destination `/user/queue/waitlist` carries private waitlist events, distinct
from the existing public `/topic/spaces/{spaceId}/availability` broadcast.

- Delivery uses `SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/waitlist", message)`.
- Dependency: `WebSocketAuthInterceptor` must set a `Principal` on the CONNECT frame whose
  `getName()` equals the userId string, or user-routing will not resolve. This is verified or
  patched during implementation.
- A `WaitlistEventPublisher` interface is added with a `@Primary` WebSocket implementation and a
  logging implementation, mirroring the existing `AvailabilityEventPublisher` so the service
  depends only on the interface.
- Message types: an offer notification (`entryId`, resource, slot, `expiresAt`) and a promotion
  confirmation (the resulting `bookingId`). To the public topic, a held slot still reads as
  `BOOKED`; the held member learns of the offer only on their private queue.

## Scheduler

`@Scheduled(fixedDelayString = "${waitlist.sweep-ms:10000}")` runs `sweepExpiredHolds()`:

1. Query `HELD` bookings with `hold_expires_at < now()` (uses the partial index), in a bounded
   batch.
2. For each, in its own transaction: lock the booking row, re-check it is still an expired hold,
   set `HELD -> EXPIRED`, set the linked entry to `EXPIRED`, then call `promoteWaitlist` for the
   resource and slot.

Single-instance assumption. Multi-node correctness would require a distributed lock around the
sweep (for example ShedLock) so only one instance promotes. Documented, not implemented.

## Caching

`HELD` counts as occupied in the availability snapshot, so cached availability remains correct.
The cache key `availability:{spaceId}:{date}` is evicted, and the matching realtime update is
published, on every state change that can alter availability: cancel, hold-create, claim, and
expire-to-available. This reuses the existing `publishAfterCommit` post-commit step.

## Failure modes

- Missed sweep or server restart: offers live in PostgreSQL, not memory, so they survive a restart
  and the next sweep reconciles them. Worst-case staleness is bounded by the sweep interval.
- Claim at the instant of expiry: the pessimistic row lock serializes claim and sweeper, producing
  a deterministic single winner.
- Promotion finds the slot re-taken (a partial-overlap edge): `findOverlapping` is non-empty under
  the lock, so that waiter is skipped and the next is tried. No invalid offer is ever issued.
- Cascade storm (every offeree declines): each decline is one bounded transaction; FIFO order is
  preserved throughout.

## Components

New package `com.tempoapi.modules.waitlist`:

- `WaitlistController` — the four endpoints.
- `WaitlistService` — join, leave, decline, claim, and `promoteWaitlist`; depends on the
  `WaitlistEventPublisher` interface, `AvailabilityCache`, and the booking and resource
  repositories.
- `dto/` — `JoinWaitlistRequest`, `WaitlistEntryResponse`, `WaitlistOfferResponse`.

New package members elsewhere:

- `entity/WaitlistEntry`, `entity/WaitlistStatus`.
- `repository/WaitlistEntryRepository`.
- `entity/BookingStatus` gains `HELD` and `EXPIRED`; `entity/Booking` gains `holdExpiresAt`.
- `websocket/WaitlistEventPublisher` (interface), `websocket/WebSocketWaitlistPublisher`
  (`@Primary`), `websocket/LoggingWaitlistPublisher`, plus the offer / confirmation message types.
- `WaitlistScheduler` (a `@Component` with the `@Scheduled` sweep); `@EnableScheduling` on the
  application or a config class.
- Exceptions `OfferExpiredException`, `AlreadyWaitlistedException`, `SlotAvailableException`, each
  mapped in `GlobalExceptionHandler` (410, 409, 400).

## Environment variables

```
WAITLIST_HOLD_SECONDS=120
WAITLIST_SWEEP_MS=10000
```

Bound in `application.properties` as `waitlist.hold-seconds` and `waitlist.sweep-ms` with the
defaults above.

## Testing

Integration tests (Testcontainers PostgreSQL + Redis, run in Codespaces):

- Cascade: A books; B then C join the waitlist. A cancels, B is offered. B declines, C is offered.
  C claims and gets a `CONFIRMED` booking. Assert every intermediate state and that exactly one
  `CONFIRMED` booking exists.
- Claim vs. expiry race: a hold with a very short window; fire the claim and the sweep
  concurrently; assert exactly one of {claimed, expired-and-cascaded} and never both.
- Hold blocks direct booking: while B holds an offer, D attempts a direct booking of the same slot
  and receives `SlotUnavailableException`.
- Constraint backstop: insert a second `HELD` row overlapping a `HELD` or `CONFIRMED` row directly
  via the repository; assert the database rejects it.
- Join validation: joining a waitlist for a currently-bookable slot returns 400.
- WebSocket: a STOMP client authenticated as B subscribes to `/user/queue/waitlist`; A cancels;
  assert B receives the offer message.

Unit tests (JUnit 5 + Mockito, run locally without Docker):

- `WaitlistService` join validation, ownership checks, claim transition, and decline cascade,
  with repositories and the publisher mocked.

## Documentation deliverables (Sprint 6, per Definition of Done)

- DEFENSE.md: a new section covering the waitlist as a second exactly-one-winner problem, holds
  enforced by the same exclusion constraint, the claim-vs-expiry race, and the scheduler with its
  single-node boundary and the ShedLock note.
- README.md: the waitlist API reference, the `/user/queue/waitlist` usage, and the lifecycle
  description.
- Postman collection: a waitlist folder (join, mine, leave/decline, claim).
- docs/websocket-testing.md: how to subscribe to the user queue and observe an offer.

## Defaults chosen

- Hold window: 120 seconds.
- Expired claim: HTTP 410 Gone.
- A held slot reads as `BOOKED` to the public topic (hold details are private to the offeree).
- Single-node scheduler; ShedLock documented as the multi-node fix, not built.
