# Tempo

Tempo is a realtime workspace booking system for co-working spaces and shared offices.
Members book desks and meeting rooms for time slots, and availability is pushed to every
connected client over WebSockets the instant it changes — when one member books a desk,
everyone else viewing that space sees it become unavailable immediately, without
refreshing.

I built Tempo around one hard problem and its correct solution: when two members try to
book the last free desk for the same time slot at the same moment, exactly one must
succeed and the other must get a clear, immediate rejection. Tempo guarantees this at the
database level so correctness never depends on lucky timing, and reflects it in the UI in
realtime so members never act on stale availability.

## Table of Contents

1. [Overview](#overview)
2. [The Concurrency Problem and How Tempo Solves It](#the-concurrency-problem-and-how-tempo-solves-it)
3. [Architecture](#architecture)
4. [Realtime Design](#realtime-design)
5. [Tech Stack](#tech-stack)
6. [Prerequisites](#prerequisites)
7. [Local Setup](#local-setup)
8. [Environment Variables](#environment-variables)
9. [API Reference](#api-reference)
10. [WebSocket Usage](#websocket-usage)
11. [Running Tests](#running-tests)
12. [Deployment](#deployment)
13. [License](#license)

## Overview

Tempo exposes a JSON REST API for authentication, space and resource management, and
booking, plus a STOMP-over-WebSocket channel that streams availability changes to
subscribers. Members book and cancel their own bookings and browse availability; admins
manage spaces and the desks and rooms inside them.

The system is organized into modules — auth, users, spaces, resources, bookings,
availability — over a PostgreSQL database accessed with Spring Data JPA, with Redis used as
a read cache, an access-token blocklist, and support for the realtime layer.

## The Concurrency Problem and How Tempo Solves It

A booking must never double-book the same resource for overlapping time. The dangerous
case is contention: two members racing for the last free slot on the same desk. Tempo
defends the invariant in two independent layers.

**Layer 1 — pessimistic row lock (the common path).** Creating a booking runs in one
transaction that first executes `SELECT ... FOR UPDATE` on the target resource row
(`ResourceRepository.findByIdForUpdate`, annotated `@Lock(PESSIMISTIC_WRITE)`). This takes
a row-level write lock, so a second transaction attempting to book the *same* resource
blocks until the first commits. With the lock held, the transaction checks for any
CONFIRMED booking whose time range overlaps the request (the Postgres `&&` range operator);
if one exists it rolls back with `SlotUnavailableException`, otherwise it inserts the
booking and commits, releasing the lock. Because the lock serializes attempts on a single
resource, the overlap check always reads a settled, committed state — two racers can never
both pass it.

**Layer 2 — database exclusion constraint (the backstop).** The `bookings` table carries a
GiST exclusion constraint (using the `btree_gist` extension):

```sql
EXCLUDE USING gist (resource_id WITH =, time_range WITH &&) WHERE (status = 'CONFIRMED')
```

The database itself refuses to store two CONFIRMED bookings for the same resource with
overlapping ranges. Even if application logic had a bug or something bypassed the lock, the
constraint rejects the conflicting insert; Tempo catches that and returns the same clean
`409`.

Time slots are stored as a half-open `[start, end)` `tstzrange`, so a 09:00–10:00 booking
and a 10:00–11:00 booking touch but do not overlap — adjacent bookings are allowed while
true overlaps are rejected.

**Why pessimistic, not optimistic, locking?** Optimistic locking (a version column, retry
on conflict) shines when contention is rare. Booking the last desk in a popular space is a
deliberate high-contention hotspot, where optimistic locking degrades into a retry storm
under exactly the load you care about. A pessimistic lock turns that thundering herd into
an orderly queue: the first attempt wins, the rest immediately see the slot is gone and
fail fast. For a low-contention path (say, editing your own profile) I would choose
optimistic locking instead, since it has zero happy-path overhead. The exclusion constraint
guarantees correctness regardless of which locking strategy is used. The full reasoning is
in [DEFENSE.md](DEFENSE.md).

## Architecture

```
com.tempoapi
  config/          SecurityConfig, JwtProperties, WebSocketConfig, DataSeeder
  filter/          JwtAuthFilter
  websocket/       WebSocketAuthInterceptor, AvailabilityEventPublisher (+ impls), messages
  exception/       GlobalExceptionHandler, domain exceptions
  util/            JwtUtil, TimeRangeUtil
  security/        AppUserDetailsService, TokenBlocklist, SecurityUtils, REST auth handlers
  common/          ApiResponse, PageResponse
  entity/          User, RefreshToken, Space, Resource, Booking, enums
  repository/      Spring Data JPA repositories
  modules/
    auth/  users/  spaces/  resources/  bookings/  availability/
```

- Every REST endpoint returns `ResponseEntity<ApiResponse<T>>` with the shape
  `{ success, message, data }`.
- A `GlobalExceptionHandler` maps domain exceptions to HTTP status codes.
- Constructor injection throughout; no field injection, no Lombok.
- All timestamps are UTC `timestamptz`; booking slots are `tstzrange`.
- Flyway owns the schema (migrations `V1`–`V6`); Hibernate never alters it.

## Realtime Design

Clients connect with STOMP over SockJS at `/ws`, authenticating by sending the JWT on the
STOMP CONNECT frame (validated by a `ChannelInterceptor` before the session is
established). They subscribe to `/topic/spaces/{spaceId}/availability`. Whenever a booking
is created or cancelled, the server — after the transaction commits — publishes an update
to that space's topic:

```json
{ "resourceId": "…", "resourceName": "Desk 1", "startTime": "…", "endTime": "…", "state": "BOOKED" }
```

The recommended client pattern is load-then-subscribe: subscribe to the topic, then GET the
availability snapshot once for the initial state, then apply each pushed delta. The booking
service depends only on an `AvailabilityEventPublisher` interface, so the transport is
decoupled from booking logic.

## Tech Stack

- Java 21, Spring Boot 3.3
- PostgreSQL (Neon), Spring Data JPA, Flyway, `btree_gist`
- Redis (availability cache, access-token blocklist)
- Spring WebSocket with STOMP over SockJS
- JWT auth (jjwt), BCrypt password hashing (strength 12)
- `hypersistence-utils` for `tstzrange` mapping
- Maven, JUnit 5, Mockito, Testcontainers

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker (only for running the test suite — Testcontainers starts PostgreSQL and Redis)
- A PostgreSQL database (Neon works well) and a Redis instance for running the app

## Local Setup

1. Clone the repository.
2. Provide the environment variables (see below). `DATABASE_URL` must be a JDBC URL such as
   `jdbc:postgresql://host/tempo?sslmode=require`. The `btree_gist` extension is enabled
   automatically by the first Flyway migration (it is available on Neon).
3. Build and run:

   ```bash
   mvn clean package -DskipTests
   java -jar target/tempo.jar
   ```

   Flyway applies all migrations on startup.

4. (Optional) Seed development data — one admin, one space, five desks and two rooms — by
   starting once with the `setup` profile and a seed password:

   ```bash
   SEED_ADMIN_PASSWORD='a-strong-password' \
     java -jar target/tempo.jar --spring.profiles.active=setup
   ```

   This creates the admin user `admin@tempo.africa` with the password you supply.

## Environment Variables

| Variable                         | Required | Default                 | Description                                            |
| -------------------------------- | -------- | ----------------------- | ------------------------------------------------------ |
| `DATABASE_URL`                   | yes      | —                       | JDBC URL for PostgreSQL (`jdbc:postgresql://…`)        |
| `DATABASE_USERNAME`              | no       | —                       | Database username (if not in the URL)                  |
| `DATABASE_PASSWORD`              | no       | —                       | Database password (if not in the URL)                  |
| `REDIS_URL`                      | yes      | —                       | Redis connection URL (`redis://host:6379`)             |
| `JWT_SECRET`                     | yes      | —                       | HMAC signing secret, at least 32 bytes                 |
| `JWT_EXPIRY_MS`                  | no       | `900000` (15 min)       | Access-token lifetime                                  |
| `JWT_REFRESH_EXPIRY_MS`          | no       | `604800000` (7 days)    | Refresh-token lifetime                                 |
| `AVAILABILITY_CACHE_TTL_SECONDS` | no       | `30`                    | TTL for cached availability snapshots                  |
| `WAITLIST_HOLD_SECONDS`          | no       | `120`                   | How long a HELD offer is valid before it expires       |
| `WAITLIST_SWEEP_MS`              | no       | `10000`                 | How often the expiry sweep runs (milliseconds)         |
| `APP_BASE_URL`                   | no       | `http://localhost:8080` | Public base URL                                        |
| `SERVER_PORT`                    | no       | `8080`                  | HTTP port                                              |
| `SEED_ADMIN_PASSWORD`            | no       | —                       | Admin password used only under the `setup` profile     |

Secrets are never hardcoded and `.env` files are never committed.

## API Reference

All responses use the envelope `{ success, message, data }`. All protected endpoints require
`Authorization: Bearer <accessToken>`. List endpoints accept `page` and `size`.

### Auth — `/api/v1/auth`

| Method | Path        | Auth   | Description                                            |
| ------ | ----------- | ------ | ----------------------------------------------------- |
| POST   | `/register` | public | Register a MEMBER; returns access + refresh tokens    |
| POST   | `/login`    | public | Authenticate; returns access + refresh tokens         |
| POST   | `/refresh`  | public | Exchange a valid refresh token for a new access token |
| POST   | `/logout`   | bearer | Revoke the refresh token, blocklist the access token  |

### Users — `/api/v1/users`

| Method | Path  | Auth   | Description               |
| ------ | ----- | ------ | ------------------------- |
| GET    | `/me` | bearer | Current user's profile    |

### Spaces — `/api/v1/spaces`

| Method | Path     | Auth  | Description                       |
| ------ | -------- | ----- | --------------------------------- |
| POST   | `/`      | ADMIN | Create a space                    |
| GET    | `/`      | bearer | List active spaces (paginated)   |
| GET    | `/{id}`  | bearer | Get one space                    |
| PUT    | `/{id}`  | ADMIN | Update a space                    |
| DELETE | `/{id}`  | ADMIN | Soft-deactivate a space           |

### Resources

| Method | Path                             | Auth  | Description                            |
| ------ | -------------------------------- | ----- | -------------------------------------- |
| POST   | `/api/v1/spaces/{spaceId}/resources` | ADMIN | Create a desk or room              |
| GET    | `/api/v1/spaces/{spaceId}/resources` | bearer | List active resources (paginated) |
| GET    | `/api/v1/resources/{id}`         | bearer | Get one resource                      |
| PUT    | `/api/v1/resources/{id}`         | ADMIN | Update a resource                      |
| DELETE | `/api/v1/resources/{id}`         | ADMIN | Soft-deactivate a resource             |

### Bookings

| Method | Path                                | Auth   | Description                                  |
| ------ | ----------------------------------- | ------ | -------------------------------------------- |
| POST   | `/api/v1/bookings`                  | bearer | Create a booking                             |
| GET    | `/api/v1/bookings/me`               | bearer | List my bookings (paginated, `?status=`)     |
| GET    | `/api/v1/bookings/{id}`             | bearer | Get a booking (owner or ADMIN)               |
| DELETE | `/api/v1/bookings/{id}`             | bearer | Cancel my booking                            |
| GET    | `/api/v1/spaces/{spaceId}/bookings` | ADMIN  | All bookings in a space for a `?date=` (UTC) |

### Availability

| Method | Path                                       | Auth   | Description                                   |
| ------ | ------------------------------------------ | ------ | --------------------------------------------- |
| GET    | `/api/v1/spaces/{spaceId}/availability`    | bearer | Snapshot of booked slots per resource (`?date=`) |

### Waitlist and auto-promotion

When a resource is fully booked for a time slot, members can join a FIFO waitlist for that
slot. If the active booking is cancelled (or a hold expires), Tempo automatically promotes
the next member in line: it creates a time-bounded exclusive HOLD on the slot and sends that
member a private offer notification over WebSocket. The member can claim the hold to confirm
the booking, or decline (or let it expire) to pass the offer to the next person in the queue.
If no one is waiting, the slot becomes publicly available again.

Held slots are protected by the same PostgreSQL GiST exclusion constraint as confirmed
bookings — a direct booker cannot take a slot that is held for someone in the queue.

**Entry lifecycle states:** `WAITING` -> `OFFERED` -> `FULFILLED`, or `EXPIRED` / `CANCELLED`.

| Method | Path                                         | Auth   | Status | Description                                                  |
| ------ | -------------------------------------------- | ------ | ------ | ------------------------------------------------------------ |
| POST   | `/api/v1/waitlist`                           | bearer | 201    | Join the waitlist for a currently-taken resource + slot      |
| GET    | `/api/v1/waitlist/mine`                      | bearer | 200    | My waitlist entries, paginated (includes live offer expiry)  |
| DELETE | `/api/v1/waitlist/{entryId}`                 | bearer | 200    | Leave (if WAITING) or decline an offer (if OFFERED); declining triggers immediate cascade to the next waiter |
| POST   | `/api/v1/waitlist/offers/{entryId}/claim`    | bearer | 200    | Claim a live offer; transitions HELD -> CONFIRMED and returns a `BookingResponse`; returns 410 Gone if the offer has expired |

**Join waitlist — request body:**

```json
{ "resourceId": "uuid", "startTime": "2026-12-01T09:00:00Z", "endTime": "2026-12-01T10:00:00Z" }
```

**Join waitlist — response `data`:**

```json
{ "id": "uuid", "position": 1, "status": "WAITING" }
```

**Validation errors:** joining a slot that is currently free returns 400; joining a slot you
are already queued for returns 409.

A ready-to-use Postman collection is in
[`docs/tempo-api.postman_collection.json`](docs/tempo-api.postman_collection.json).

## WebSocket Usage

- Connect (with SockJS) to `/ws`.
- Send the JWT on the STOMP CONNECT frame as `Authorization: Bearer <accessToken>`.
  Connections without a valid token are rejected.
- Subscribe to `/topic/spaces/{spaceId}/availability` for public availability deltas.
  Receive `{ resourceId, resourceName, startTime, endTime, state }` where `state` is
  `BOOKED` or `AVAILABLE`. Held slots are broadcast as `BOOKED` so they appear unavailable
  to members who are not the offer recipient.
- Subscribe to `/user/queue/waitlist` for private waitlist offer notifications. This
  destination is user-scoped: each member receives only their own offers. The message shape
  is `{ entryId, resourceId, resourceName, startTime, endTime, expiresAt, state }` where
  `state` is `OFFERED` (a new hold has been created for you) or `GRANTED` (your claim was
  confirmed).

A copy-paste browser test page and `wscat` instructions are in
[`docs/websocket-testing.md`](docs/websocket-testing.md).

## Running Tests

```bash
mvn clean test
```

The integration tests use **Testcontainers**, which requires a running Docker engine — they
start a real PostgreSQL (H2 cannot support `tstzrange`, `btree_gist`, or the exclusion
constraint) and a real Redis. The most important test spins up two threads that attempt to
book the same resource for the same slot simultaneously and asserts that exactly one
succeeds, the other receives `SlotUnavailableException`, and exactly one CONFIRMED booking
exists afterward. A separate test inserts two overlapping bookings directly through the
repository to prove the database exclusion constraint independently of the application lock.

Pure unit tests (no Docker) can be run on their own, for example:

```bash
mvn test -Dtest=AuthServiceTest,BookingServiceTest,JwtUtilTest
```

## Deployment

Tempo deploys as a single JAR on EC2 (Ubuntu) behind Nginx, managed by systemd. The Nginx
config must forward the WebSocket upgrade headers for `/ws`. Full instructions and the
service, Nginx, and environment templates are in [`deploy/`](deploy/).

## License

MIT — see [LICENSE](LICENSE).
