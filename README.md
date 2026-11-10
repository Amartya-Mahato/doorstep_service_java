# Doorstep Service Booking

A **Spring Boot 3 / Java 17 / PostgreSQL** backend for booking doorstep vehicle-battery services. It checks technician availability and assigns a qualified technician based on skill, service zone, working hours and existing bookings. Concurrent requests for the same slot cannot double-book a technician.

- [DESIGN.md](DESIGN.md): design decisions
- [API_TESTING.md](API_TESTING.md): step-by-step manual API tests
- Swagger UI: `http://localhost:8080/swagger-ui.html` (while the app is running)

## Requirements

- JDK 17 or later
- Docker (only for the Docker setup)

Maven does not need to be installed. The project includes the Maven Wrapper, which downloads Maven on first use. Use `./mvnw` on Linux/macOS and `.\mvnw` on Windows (PowerShell or Command Prompt).

## Run the Application

### With Docker (recommended)

Starts the app and PostgreSQL 16:

```sh
docker compose up --build
```

- API: `http://localhost:8080`
- PostgreSQL: `localhost:5432`
- Flyway applies the schema and seed data on startup.

### Without Docker (in-memory H2)

No database setup needed. Data is reset on every start.

Linux/macOS:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows:

```powershell
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

### With your own PostgreSQL

By default the app connects to `jdbc:postgresql://localhost:5432/doorstep` with username `doorstep` and password `doorstep`. Override with the `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` environment variables.

```sh
./mvnw spring-boot:run     # Linux/macOS
.\mvnw spring-boot:run     # Windows
```

If port 8080 is already in use, add `-Dspring-boot.run.arguments=--server.port=8081` (quote the whole argument in PowerShell).

## API Documentation (Swagger)

With the app running:

| URL | Content |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI: browse and try every endpoint |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3 spec (JSON), for Postman or code generators |

Swagger UI lists each endpoint's parameters, request/response schemas, and the status and error codes it can return.

## API Overview

| Endpoint | Success | Errors |
|---|---|---|
| `GET /availability?serviceId=&pincode=&date=YYYY-MM-DD` | `200` bookable start times | `400`, `404` (service), `422` (pincode) |
| `POST /bookings` (optional `Idempotency-Key` header) | `201` created, `200` idempotent retry | `400`, `404`, `409 NO_AVAILABILITY`, `422` |
| `GET /bookings/{id}` | `200` | `400` (not a UUID), `404` |
| `POST /bookings/{id}/cancel` | `200` cancelled booking | `404`, `409 CANCEL_TOO_LATE` / `INVALID_STATE` |
| `GET /technicians/{id}/schedule?date=YYYY-MM-DD` | `200` bookings ordered by start time | `400`, `404` |
| `GET /bookings?phone=&page=0&size=20` | `200` paginated bookings | `400` |
| `GET /actuator/health` | `200` | |

Create-booking request body:

```json
{
  "serviceId": 1,
  "pincode": "560001",
  "startTime": "2026-11-12T10:00:00",
  "customerPhone": "9876543210"
}
```

Example (Linux/macOS, Git Bash, WSL):

```sh
curl -X POST http://localhost:8080/bookings \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: abc-1" \
  -d '{"serviceId":1,"pincode":"560001","startTime":"2026-11-12T10:00:00","customerPhone":"9876543210"}'
```

Example (Windows PowerShell):

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/bookings `
  -ContentType "application/json" `
  -Headers @{ "Idempotency-Key" = "abc-1" } `
  -Body '{"serviceId":1,"pincode":"560001","startTime":"2026-11-12T10:00:00","customerPhone":"9876543210"}'
```

`startTime` must be 2 hours to 7 days from now, so change the date before running these examples.

### Error Response

Every error has the same shape:

```json
{
  "timestamp": "2026-11-10T18:06:55.852Z",
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "Request validation failed",
  "path": "/bookings",
  "details": ["customerPhone: must be 10-15 digits, optional leading +"]
}
```

`details` is only present for field validation errors.

| Code | Status | Meaning |
|---|---|---|
| `INVALID_REQUEST` | 400 | Validation failed, or a missing or malformed parameter or body |
| `NOT_FOUND` | 404 | Service, booking, technician or URL does not exist |
| `PINCODE_NOT_SERVICEABLE` | 422 | Pincode is not in any zone |
| `INVALID_SLOT` | 422 | `startTime` is not on a 30-minute boundary |
| `OUTSIDE_BOOKING_WINDOW` | 422 | `startTime` is less than 2 hours or more than 7 days away |
| `NO_AVAILABILITY` | 409 | No qualified technician is free |
| `CANCEL_TOO_LATE` | 409 | Less than 1 hour before start |
| `INVALID_STATE` | 409 | Booking is not `CONFIRMED` |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

## Seed Data

Flyway loads 3 services, 2 zones with 3 pincodes each, and 6 technicians working 08:00–20:00. See [V2__seed.sql](src/main/resources/db/migration/V2__seed.sql) (schema: [V1__schema.sql](src/main/resources/db/migration/V1__schema.sql)) and the tables in [API_TESTING.md](API_TESTING.md#seed-data).

## Business Rules

- **Time zone:** all times are IST. `startTime` is a local date-time without offset. The app uses a single injectable `Clock`, so tests can control the time.
- **Slots:** start times are on 30-minute boundaries, and the job must finish within the technician's shift.
- **Booking window:** at least 2 hours and at most 7 days ahead (both inclusive). Availability for dates outside the window returns an empty `slots` list.
- **Travel buffer:** a technician needs at least 30 minutes between jobs. Exactly 30 minutes is allowed.
- **Assignment:** among technicians with the right skill and zone and no conflicting booking, pick the one with the fewest non-cancelled bookings that day, then the lowest ID.
- **Cancellation:** only `CONFIRMED` bookings, up to 1 hour before start (inclusive). `COMPLETED` exists in the model, but no endpoint sets it yet.
- **Idempotency:** `Idempotency-Key` is optional and globally unique (at most 255 characters). A repeated key returns the original booking even if the body differs.
- **Validation:** phone is 10–15 digits with an optional leading `+`, stored as sent. Pincode is 6 digits, and each pincode belongs to exactly one zone (enforced by a unique constraint).
- **Schedule:** a technician's schedule excludes cancelled bookings.
- **Status codes:** `400` malformed input, `404` unknown resource, `422` well-formed but breaks a booking rule, `409` no capacity or wrong booking state.

Not included: authentication, rate limiting (discussed in DESIGN.md), admin APIs and notifications.

## Running Tests

No Docker needed. Tests use in-memory H2 in PostgreSQL compatibility mode.

```sh
./mvnw test                               # Linux/macOS
.\mvnw test                               # Windows
./mvnw test -Dtest=ConcurrentBookingTest  # one class (Windows: .\mvnw test "-Dtest=ConcurrentBookingTest")
```

| Test class | Covers |
|---|---|
| `AssignmentPolicyTest` | Overlap and 30-minute buffer, shift and slot boundaries, skill/zone eligibility, booking window, cancellation cutoff, fewest-bookings and lowest-ID tie-break |
| `BookingApiIntegrationTest` | End-to-end HTTP flows, idempotency, status codes and error body, cancellation freeing slots, pagination, health |
| `ConcurrentBookingTest` | 10–12 parallel requests for the last free technician (exactly one `201`, the rest `409`), overlapping start times, parallel retries with one `Idempotency-Key`, and database consistency checks |

### Troubleshooting (Windows)

If the app or tests fail with `Unable to establish loopback connection` / `Invalid argument: connect` (a JDK Unix-domain-socket issue on some Windows setups), point the socket temp dir at a short path:

```powershell
mkdir C:\jtmp
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\jtmp"   # Command Prompt: set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\jtmp
```

Flyway 9.22 logs a warning that PostgreSQL 16 is newer than its tested version. It can be ignored.

## Project Structure

```text
src/main/java/com/doorstep
├── domain/   Entities and AssignmentPolicy (pure assignment rules, no Spring)
├── repo/     Spring Data repositories, including the SELECT ... FOR UPDATE lock
├── service/  AvailabilityService, BookingCreator (transactional create), BookingService
├── web/      Controllers, DTOs, error handling
└── config/   Clock bean, X-Request-Id filter (request id in logs)
```

Concurrency is handled by locking every eligible technician row before assigning one, so two requests cannot both take the same technician. See [DESIGN.md](DESIGN.md) for details.
