# Architecture and correctness

Tempo is a single Spring Boot application backed by PostgreSQL and Redis. REST controllers accept validated DTOs. Services own transaction boundaries. A local STOMP broker sends notifications to connected clients.

## Booking integrity

Creating a booking locks its resource row with `SELECT ... FOR UPDATE`. Booking, cancellation, waitlist enrollment, claim, decline and expiry all acquire the resource lock before locking a booking or queue entry. Resource edits use the same resource lock. Competing operations on one resource therefore inspect the last committed state after obtaining the lock.

PostgreSQL independently rejects overlapping `CONFIRMED` or `HELD` rows through the GiST exclusion constraint. Ranges use `[start, end)` semantics, so adjacent bookings are allowed. Redis never decides whether a booking is permitted.

Space hours and calendar dates use UTC. Requests may contain other offsets, but validation normalizes them before checking dates and hours. A conditional database update checks current space activity and opening hours while advancing its availability revision. This orders new occupancy against concurrent space edits. Existing confirmed bookings survive soft deactivation.

## Waitlists

A queue is for one resource and the exact time range of an existing booking or hold. FIFO order is creation time, with entry ID as a deterministic tie breaker. A queue for another interval is independent.

Cancellation, decline or expiry releases occupancy and promotes the next eligible waiter in the same transaction. A failed hold insert rolls back the release. There is no committed gap in which a normal direct booker can take the slot before its next hold is created.

Promotion checks current resource, space and user activity, the requested start time and opening hours. Obsolete entries become `EXPIRED`. A hold expires at the earlier of the configured offer duration and the slot start. Claim checks eligibility and expiry again. The sweeper retries failed expiry transactions on later runs.

## Availability and caching

Every committed occupancy or administrative change advances the space's PostgreSQL availability revision. Revisions are part of Redis keys: `availability:{spaceId}:{date}:{revision}`. A cache-miss reader that finishes after a newer commit can only populate its older revision's key. It cannot replace the key used by a later revision.

Snapshot reads use a PostgreSQL repeatable-read transaction. Space activity, revision, resources and occupied ranges come from that database snapshot. The active-space check precedes cache use. Redis failures fall back to PostgreSQL; old cache entries expire through their TTL.

Advancing the revision takes a short space-row write lock until commit. Writes to different resources in the same space can wait at that point. This is a deliberate consistency trade-off; throughput has not been benchmarked.

A snapshot is consistent at its transaction snapshot, rather than a promise that no concurrent commit can happen afterward. A revision identifies that snapshot; it is not a durable event cursor.

## Notifications

Public availability notifications and private waitlist notifications run after successful commit. Rollback suppresses them. Notification failures are logged without changing the result of an already committed booking. Slot release and promotion remain database work, never notification callbacks.

The in-process simple broker provides best-effort delivery to connected clients. It has no durable queue, replay or exactly-once guarantee. Clients subscribe, wait for registration, fetch a snapshot and treat every availability notification as a reason to fetch again. They must also fetch again on reconnect and periodically while displaying availability. Compare response revisions to avoid applying an older HTTP response after a newer one. Coalesce refresh requests when several events arrive together.

`BOOKED` and `AVAILABLE` include the affected interval. `CHANGED` announces resource/space metadata changes and carries null slot fields. Clients use all three as invalidation signals. Booking responses and fresh REST snapshots are authoritative.

## Security boundaries

Passwords are BCrypt hashes. Refresh credentials are fixed-expiry reusable JWTs; PostgreSQL stores only their SHA-256 digests. Logout requires an access token and a refresh credential owned by the same account. It revokes that refresh credential and blocklists the presented access token. Other previously issued access tokens retain their own expiry. Refresh rotation and token-family reuse detection are not implemented.

REST authentication and STOMP CONNECT check the Redis revocation blocklist. An unavailable blocklist fails closed. REST returns 503; WebSocket access is rejected or delivery suppressed. Redis data loss or deliberate key eviction can still lose revocation records, so deployments must preserve Redis data and avoid evicting those keys.

The STOMP interceptor rejects client SEND frames and accepts subscriptions only to space availability topics and the caller's `/user/queue/waitlist` destination. All authenticated members may view all spaces; there is no tenant membership model. Outbound authorization checks blocklist and expiry for each delivery, including passive subscribers. Session records contain token ID and expiry, not the raw token, and are removed on disconnect. An expired connection must reconnect with a valid token. Already-authorized messages in transit cannot be recalled.

Access-token roles reflect issuance time and remain bounded by access-token expiry. New booking and waitlist operations independently check account eligibility. Browser WebSocket origins use an explicit configured allowlist. Public HTTP hosting requires TLS and appropriate network restrictions.

## Deployment boundary

The supported scope is one application instance. PostgreSQL row locks also coordinate booking integrity between processes sharing the database, but the local broker does not distribute notifications between processes. Multi-instance delivery, durable events and replay would require additional design and verification. They are outside the current demonstration scope.
