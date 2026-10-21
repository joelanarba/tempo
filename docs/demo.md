# Demonstrating Tempo

Use a disposable local database. This guide creates users, bookings and queue entries. All times are UTC.

## Start and verify dependencies

From the repository root, run `scripts/start-local.ps1` in PowerShell. It creates an ignored `.env`, starts PostgreSQL and Redis with Compose, packages the application and runs its setup profile. Keep that terminal open.

Run `scripts/smoke-test.ps1` in another terminal. It checks readiness, authentication, a booking conflict, ownership, a waitlist hold, claim, availability and logout. Run `mvn clean verify` for the concurrency tests.

For a remote demo, use the [Codespaces tunnel](../README.md#codespaces). The setup profile seeds `admin@tempo.africa` using `SEED_ADMIN_PASSWORD` from `.env`.

## Manual sequence

Import `tempo-api.postman_collection.json` into Postman. Use a private environment for `admin_password`, `member_password` and `waiter_password`. Do not export credentials or captured tokens. The collection generates a future UTC date; use its `slot_start` and `slot_end` for both booking and queue enrollment.

1. Check health, register the member and waiter, and log in as the administrator.
2. Create a space with UTC opening hours 08:00-20:00 and a desk in that space. The response scripts capture their IDs.
3. Open two clients using the [WebSocket guide](websocket-testing.md), one with the member token and one with the waiter token. Subscribe before performing mutations.
4. Create the member booking. Both clients should receive an availability event. Fetch the snapshot and record its revision and occupied interval.
5. Attempt the same booking as the waiter. Expect 409. Attempt cancellation as the waiter. Expect 403. The original booking must remain confirmed.
6. Join the waiter to the exact booked interval. Save the returned entry ID.
7. Cancel as the member. The transaction cancels the booking and creates a hold for the waiter. The waiter receives a private `OFFERED` event. The member must not receive it. Availability stays occupied; a public `BOOKED` event signals the new hold.
8. Claim as the waiter before `expiresAt`. Expect a confirmed booking and private `GRANTED` event. A second overlapping booking must still return 409.
9. Cancel the claimed booking with no remaining waiters. Both clients should receive `AVAILABLE`. A fresh snapshot should show no occupied interval.
10. Log out the waiter. Reusing that access token on `/api/v1/users/me` must return 401. That session must stop receiving protected WebSocket messages.

Use a third registered member to show FIFO cascade: join both waiters to the same interval, cancel the owner, then decline the first offer or wait for expiry and the next sweep. Only the next eligible waiter should receive the replacement offer. Query the waitlist list endpoint to recover state if a private event is missed.

The collection also contains optional administrative edits and cleanup requests. Run those deliberately after the demonstration. Deactivation preserves booking history.
