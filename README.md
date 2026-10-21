# Tempo

A workspace-booking backend built with Java 21, Spring Boot, PostgreSQL, Redis and STOMP over WebSockets. Members book desks and rooms, cancel their own bookings, view availability and join exact-slot waitlists. Administrators manage spaces and resources.

## Booking correctness

Booking and waitlist mutations acquire a PostgreSQL resource-row lock. A GiST exclusion constraint independently prevents overlapping `CONFIRMED` and `HELD` bookings. Time ranges use `[start, end)`, allowing adjacent slots. Opening hours and calendar dates use UTC.

A cancellation, decline or expiry releases its slot and promotes the next eligible waiter in one transaction. Notifications run only after commit. Redis is used for availability caching and access-token revocation, never to decide whether bookings overlap.

See [architecture and security boundaries](docs/architecture.md) for the transaction model, cache revisions, failure policy and delivery limitations.

## Local setup

Requirements: Java 21, Maven 3.9+, Docker Engine 25+ and Docker Compose v2. Windows also needs working virtualization if Docker uses WSL 2.

From PowerShell:

```powershell
.\scripts\start-local.ps1
```

The script creates a private, ignored `.env` with random local credentials if that file does not exist. It starts PostgreSQL 16 and Redis 7 on loopback interfaces, packages the JAR and runs the `setup` profile. That profile seeds one administrator, one space, five desks and two rooms. The administrator email is `admin@tempo.africa`; its password is the generated `SEED_ADMIN_PASSWORD` value in `.env`. Keep this file private.

Run the local REST smoke test in a second terminal:

```powershell
.\scripts\smoke-test.ps1
```

It creates disposable users and resources, verifies conflict/ownership/hold/claim/logout behavior and soft-deactivates its demo resources. It prints no credentials. Follow the [demonstration guide](docs/demo.md) to observe WebSocket updates as well.

Stop local dependencies while preserving data:

```powershell
docker compose stop
```

For an environment managed separately, provide the variables below, then use `mvn package` and `java -jar target/tempo.jar`. Copying `.env.example` alone does not configure a Java process; the PowerShell script explicitly loads it.

| Variable | Purpose | Default |
| --- | --- | --- |
| `DATABASE_URL` | PostgreSQL JDBC URL | Required |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Database credentials | Empty |
| `REDIS_URL` | Redis connection URL | Required |
| `JWT_SECRET` | Random HMAC signing key, at least 32 bytes | Required |
| `JWT_EXPIRY_MS` | Access-token lifetime | 900000 |
| `JWT_REFRESH_EXPIRY_MS` | Fixed refresh-token lifetime | 604800000 |
| `AVAILABILITY_CACHE_TTL_SECONDS` | Cache entry lifetime | 30 |
| `WAITLIST_HOLD_SECONDS` | Offer duration, capped by slot start | 120 |
| `WAITLIST_SWEEP_MS` | Expiry sweep interval | 10000 |
| `WEBSOCKET_ALLOWED_ORIGINS` | Comma-separated browser origin allowlist | `http://localhost:8080` |
| `SERVER_PORT` | Application port | 8080 |
| `SERVER_ADDRESS` | Bind interface | `127.0.0.1` |
| `SEED_ADMIN_PASSWORD` | Used only with the `setup` profile | Empty |

`POSTGRES_PASSWORD` belongs to the local Compose setup; the startup script supplies it to the application as `DATABASE_PASSWORD`. Flyway owns the schema through migrations V1-V10. Hibernate does not modify it. V10 converts stored refresh credentials to SHA-256 digests.

## Tests and verification

```powershell
# All tests, including real PostgreSQL and Redis through Testcontainers.
mvn clean verify

# Explicit partial run without Docker, including real WebSocket transport checks.
mvn '-DexcludedGroups=integration' test
```

The full suite passes 162 tests, including PostgreSQL constraints and transaction races, Redis caching and revocation, and WebSocket delivery and access control. The REST smoke script also passes against the packaged application with Compose. The partial command excludes the database and Redis integration tests.

[GitHub Actions](https://github.com/joelanarba/tempo/actions/workflows/ci.yml) runs the full Java 21 Maven build with Docker and saves the test reports.

## Codespaces

The [development container](.devcontainer/devcontainer.json) defines Java 21, Maven, Docker and SSH support. Run `mvn clean verify` from the repository root. Tests create their own PostgreSQL and Redis containers and do not need `.env` or external service credentials.

For a demo running on port 8080 in a Codespace, open a loopback-only tunnel from your computer:

```powershell
gh codespace ssh -c YOUR_CODESPACE -- -N -L 127.0.0.1:8080:127.0.0.1:8080
```

Use `http://localhost:8080` as the API URL while that connection is open.

## REST API

Responses use `{ success, message, data }`. Protected endpoints require `Authorization: Bearer <access-token>`. Registration and login return access and refresh credentials. Refresh issues another access token while preserving the refresh credential's original expiry. Logout requires an access token and an owned refresh credential. Redis revocation failures return 503.

| Method | Path | Access |
| --- | --- | --- |
| GET | `/health` | Public dependency readiness |
| POST | `/api/v1/auth/register` | Public |
| POST | `/api/v1/auth/login` | Public |
| POST | `/api/v1/auth/refresh` | Refresh credential |
| POST | `/api/v1/auth/logout` | Authenticated owner |
| GET | `/api/v1/users/me` | Member or administrator |
| GET, POST | `/api/v1/spaces` | Read: authenticated; create: administrator |
| GET, PUT, DELETE | `/api/v1/spaces/{id}` | Read: authenticated; mutation: administrator |
| GET, POST | `/api/v1/spaces/{spaceId}/resources` | Read: authenticated; create: administrator |
| GET, PUT, DELETE | `/api/v1/resources/{id}` | Read: authenticated; mutation: administrator |
| POST | `/api/v1/bookings` | Authenticated |
| GET | `/api/v1/bookings/me` | Own bookings, paginated, optional `status` |
| GET | `/api/v1/bookings/{id}` | Owner or administrator |
| DELETE | `/api/v1/bookings/{id}` | Owner |
| GET | `/api/v1/spaces/{spaceId}/bookings?date=YYYY-MM-DD` | Administrator |
| GET | `/api/v1/spaces/{spaceId}/availability?date=YYYY-MM-DD` | Authenticated |
| POST | `/api/v1/waitlist` | Authenticated, exact occupied interval |
| GET | `/api/v1/waitlist/mine` | Own entries, paginated |
| DELETE | `/api/v1/waitlist/{entryId}` | Owner; leave or decline |
| POST | `/api/v1/waitlist/offers/{entryId}/claim` | Owner of live offer |

Booking/waitlist bodies contain `resourceId`, `startTime` and `endTime`. Conflict returns 409, validation returns 400, missing authentication returns 401, ownership/role violations return 403 and an expired offer returns 410. Space/resource deletion is soft deactivation; existing confirmed bookings remain in history.

The [Postman collection](docs/tempo-api.postman_collection.json) includes request examples and response checks. Set its credential variables privately. Use individual requests in the [demo sequence](docs/demo.md); the collection includes optional administrative mutations and is not a concurrency benchmark.

## Real-time availability

Connect using SockJS at `/ws` and provide the access token on STOMP CONNECT. Client SEND frames are rejected. Subscribe to `/topic/spaces/{spaceId}/availability` or your own `/user/queue/waitlist` destination. Revoked and expired sessions stop receiving protected messages, including passive subscribers.

Public events have state `BOOKED`, `AVAILABLE` or `CHANGED`. Fetch a snapshot after every event, on reconnect and periodically while displaying availability. A snapshot includes its database revision. Delivery is best effort from one application's in-process broker; there is no replay or durable-event guarantee. The [WebSocket guide](docs/websocket-testing.md) contains a browser client and registration guidance.

## Deployment

The [deployment guide](deploy/README-deployment.md) covers the JAR, systemd, Redis and Nginx setup for one application instance. Use TLS for public access.

## License

[MIT](LICENSE).
