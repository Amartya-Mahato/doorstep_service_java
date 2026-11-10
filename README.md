# Doorstep Service Booking

A backend service built using **Spring Boot 3, Java 17, and PostgreSQL** for booking doorstep vehicle-battery services. The application checks technician availability and automatically assigns a qualified technician based on their skills, service zone, working hours, and existing bookings.

The booking system is designed to prevent double-bookings, even when multiple requests are made at the same time.

For more details, refer to [DESIGN.md](DESIGN.md) for the design decisions and [API_TESTING.md](API_TESTING.md) for manual API testing instructions.

## Run the Application

### Using Docker (Recommended)

Start the application and PostgreSQL database with:

```bash
docker compose up --build
```

- Application: `http://localhost:8080`
- PostgreSQL: `localhost:5432`
- Database schema and seed data are applied automatically using Flyway.

### Without Docker (Local Profile)

The application can also run locally using an in-memory H2 database. No separate database setup is required.

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Using Your Own PostgreSQL Database

The default configuration expects a PostgreSQL database with the following credentials:

- Database: `doorstep`
- Username: `doorstep`
- Password: `doorstep`
- Host: `localhost`
- Port: `5432`

Start the application using:

```bash
mvn spring-boot:run
```

You can override the database configuration using the following environment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Running Tests

Run the complete test suite using:

```bash
mvn test
```

**Requirements:**
- JDK 17 or later
- Maven

Docker is not required. Tests run against an in-memory H2 database configured in PostgreSQL compatibility mode.

### Test Coverage

| Test Class | What It Covers |
|---|---|
| `AssignmentPolicyTest` | Booking overlaps, 30-minute buffer rules, shift boundaries, technician skills and service zones, slot boundaries, booking lead time, cancellation cutoff, technician selection based on the fewest bookings, and tie-breaking rules. |
| `BookingApiIntegrationTest` | Complete API workflows, idempotency, HTTP status codes and error responses, cancellation and slot availability, pagination, and health checks. |
| `ConcurrentBookingTest` | Handles 10–12 parallel HTTP requests for the same slot and verifies that exactly the available number of technicians get assigned while the remaining requests receive `409` responses. Also covers overlapping bookings with different start times, concurrent retries using the same `Idempotency-Key`, and database consistency checks. |

To run only the concurrency tests:

```bash
mvn test -Dtest=ConcurrentBookingTest
```

This test is particularly useful for verifying that the booking system handles simultaneous requests correctly.

### Environment-Specific Troubleshooting

**Windows with JDK 17.0.21**

If the application or tests fail with an error similar to:

```text
Unable to establish loopback connection
Invalid argument: connect
UnixDomainSockets
```

This can be resolved by configuring a temporary directory for Unix domain sockets.

Create the directory once:

```powershell
mkdir C:\jtmp
```

Then set the following environment variable before running Maven:

```powershell
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\jtmp"
```

### Verification

The application was tested using Docker Compose with PostgreSQL 16.

The following scenarios were verified:

- Successful application startup and database migrations.
- Idempotent booking retries.
- A 10-request parallel booking race for the last available technician.
- Exactly one request received `201 Created`, while the remaining nine received `409 Conflict`.
- Database verification confirmed that each technician had only one corresponding booking for the contested slot.

Flyway 9.22 displays a harmless warning indicating that PostgreSQL 16 is newer than its tested version.

## API Overview

| Endpoint | Successful Response | Possible Errors |
|---|---|---|
| `GET /availability?serviceId=&pincode=&date=YYYY-MM-DD` | `200` – Returns available slots for the requested service, pincode, and date. | `400`, `404` (service), `422` (pincode) |
| `POST /bookings` | `201` – New booking created. `200` – Existing booking returned for an idempotent retry. | `400`, `404`, `422`, `409 NO_AVAILABILITY` |
| `GET /bookings/{id}` | `200` – Returns booking details. | `404`, `400` (invalid UUID) |
| `POST /bookings/{id}/cancel` | `200` – Returns the cancelled booking. | `404`, `409 CANCEL_TOO_LATE` / `INVALID_STATE` |
| `GET /technicians/{id}/schedule?date=` | `200` – Returns the technician's bookings for the specified date, ordered by start time. | `404` |
| `GET /bookings?phone=&page=&size=` | `200` – Returns paginated booking records. | `400` |
| `GET /actuator/health` | `200` – Application health status. | — |

### Request and Response Details

**1. Check Availability**

```http
GET /availability?serviceId=&pincode=&date=YYYY-MM-DD
```

Successful response (`200`):

```json
{
  "serviceId": 1,
  "pincode": "560001",
  "date": "2026-11-12",
  "slots": ["ISO local datetimes"]
}
```

**2. Create a Booking**

```http
POST /bookings
```

Optional header:

```http
Idempotency-Key: abc-1
```

Request body:

```json
{
  "serviceId": 1,
  "pincode": "560001",
  "startTime": "2026-11-12T10:00:00",
  "customerPhone": "9876543210"
}
```

A new booking returns `201 Created`. If the same idempotency key is submitted again, the original booking is returned with `200 OK`.

**3. Get Booking Details**

```http
GET /bookings/{id}
```

**4. Cancel a Booking**

```http
POST /bookings/{id}/cancel
```

**5. View a Technician's Schedule**

```http
GET /technicians/{id}/schedule?date=2026-11-12
```

**6. List Customer Bookings**

```http
GET /bookings?phone=&page=&size=
```

The response includes:

- `content`
- `page`
- `size`
- `totalElements`
- `totalPages`

**7. Application Health**

```http
GET /actuator/health
```

### Standard Error Response

All API errors follow a consistent response structure:

```json
{
  "timestamp": "...",
  "status": 409,
  "code": "NO_AVAILABILITY",
  "message": "...",
  "path": "...",
  "details": "..."
}
```

The `details` field is optional.

The application uses the following error codes:

| Error Code | Description |
|---|---|
| `INVALID_REQUEST` | Request validation failed. |
| `NOT_FOUND` | Requested resource does not exist. |
| `PINCODE_NOT_SERVICEABLE` | The requested pincode is not serviceable. |
| `INVALID_SLOT` | Requested time does not match a valid booking slot. |
| `OUTSIDE_BOOKING_WINDOW` | Requested booking is outside the allowed time window. |
| `NO_AVAILABILITY` | No qualified technician is available. |
| `CANCEL_TOO_LATE` | Cancellation was requested after the allowed cutoff. |
| `INVALID_STATE` | Booking cannot be cancelled in its current state. |
| `INTERNAL_ERROR` | Unexpected server-side error. |

### Example API Request

```bash
curl -X POST localhost:8080/bookings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: abc-1' \
  -d '{"serviceId":1,"pincode":"560001","startTime":"2026-11-12T10:00:00","customerPhone":"9876543210"}'
```

Additional API examples, including expected responses, are available in [API_TESTING.md](API_TESTING.md).

## Seed Data

The application comes with initial data to make local setup and testing easier.

| Data | Details |
|---|---|
| Services | 3 services |
| Service Zones | 2 zones |
| Serviceable Pincodes | 3 pincodes per zone |
| Technicians | 6 technicians |
| Working Shifts | 08:00–20:00 |

The database schema and initial data are maintained through Flyway migrations:

- Schema: [V1__schema.sql](src/main/resources/db/migration/V1__schema.sql)
- Seed data: [V2__seed.sql](src/main/resources/db/migration/V2__seed.sql)

## Business Rules and Assumptions

The following rules define how bookings, technician assignments, and cancellations work.

### 1. Time Handling

- All application times are handled in IST.
- `startTime` is accepted as a local date-time without a timezone offset.
- The server uses a single injectable `Clock` instance for time-dependent operations, making the logic easier to test consistently.

### 2. Booking Buffer

A 30-minute buffer is maintained between consecutive jobs assigned to the same technician.

- A booking blocks the technician from its start time until 30 minutes after its end time.
- A new booking must also leave at least 30 minutes between its end time and the next booking's start time.
- Exactly 30 minutes between jobs is allowed.

### 3. Booking Lead Time

Bookings must be made at least 2 hours and no more than 7 days in advance.

Both boundaries are inclusive.

### 4. Technician Assignment and Tie-Breaking

When multiple technicians qualify for a booking, the system follows these rules:

- Only technicians with the required skills and service-zone eligibility are considered.
- Existing bookings, including buffer periods, are checked before assignment.
- The technician with the fewest non-cancelled bookings on that calendar day is preferred.
- If multiple technicians have the same booking count, the technician with the lowest ID is selected.

This implements the rule 6 tie-break condition.

### 5. Cancellation Rules

- A booking can be cancelled until one hour before its scheduled start time.
- Cancellation is allowed when `now <= start - 1 hour`, including the exact cutoff.
- Only bookings in the `CONFIRMED` state can be cancelled.
- Attempting to cancel a booking in any other state returns `409 INVALID_STATE`.
- `COMPLETED` is included in the booking model, but no endpoint currently changes a booking to that state.

### 6. Idempotency

- The `Idempotency-Key` header is optional.
- The key alone identifies a request.
- Repeating a request with the same key returns the original booking, even if the new request body is different.
- The request body is not compared against the original request.
- Keys are globally unique and are not scoped to individual customers.

### 7. Availability Checks

Availability is calculated using the following conditions:

- The requested date must fall within the 2-hour to 7-day booking window.
- Technician shifts are taken into account.
- Existing bookings and buffer periods are considered.

Dates in the past or beyond the permitted booking window return an empty `slots` array.

### 8. HTTP Status Codes

The API distinguishes between invalid requests, business-rule violations, and booking conflicts.

| Status Code | Usage |
|---|---|
| `400 Bad Request` | Request validation or formatting errors. |
| `404 Not Found` | Requested resource does not exist. |
| `422 Unprocessable Entity` | Well-formed requests that violate business rules, such as invalid slots, bookings outside the permitted window, or unserviceable pincodes. |
| `409 Conflict` | No available technician or a booking state conflict. |

### 9. Input Validation

- Phone numbers must contain 10–15 digits, with an optional leading `+`.
- Phone numbers are stored without normalization.
- Pincodes must contain exactly 6 digits.
- Each pincode belongs to exactly one service zone, enforced through a unique database constraint.

### 10. Technician Schedule

The technician schedule endpoint returns bookings ordered by their start time.

Cancelled bookings are excluded from the response.

### 11. Features Not Included

The current implementation focuses on the core booking and technician-assignment workflow. The following features are outside the scope of this version:

- Rate limiting (covered in `DESIGN.md`).
- Authentication and authorization.
- Admin APIs.
- Customer notifications.
- Maven Wrapper (`mvnw`).

## Project Structure

The codebase is organized into separate layers to keep business logic, database operations, and API handling independent.

```text
src/main/java/com/doorstep
│
├── domain/
│   └── Entities and AssignmentPolicy
│       (Business entities and standalone assignment rules)
│
├── repo/
│   └── Spring Data repositories
│       (Database access, including the FOR UPDATE query)
│
├── service/
│   ├── AvailabilityService
│   ├── BookingCreator
│   └── BookingService
│       (Availability checks, transactional booking creation,
│        idempotency, cancellation, and read operations)
│
├── web/
│   └── Controllers, DTOs, and error handling
│       (REST API endpoints and request/response handling)
│
└── config/
    ├── Clock bean
    └── Request-ID filter
        (X-Request-Id handling and logging MDC)
```

## Design and Implementation Notes

The main focus of the implementation is to ensure reliable booking creation, correct technician assignment, and consistent behaviour under concurrent requests.

The assignment rules are kept separate from the Spring application layer, while booking creation and database operations are handled transactionally.

Concurrency is handled by locking all eligible technician candidates before making an assignment. This prevents multiple simultaneous requests from assigning overlapping jobs to the same technician.

Idempotency is also supported so that retries using the same key do not create duplicate bookings.

The implementation and its design decisions are documented in [DESIGN.md](DESIGN.md). The test suite includes unit tests, API integration tests, and concurrent booking scenarios.

**Test status:** All 32 tests passed with `mvn clean test` in the development environment, including real-HTTP integration tests and concurrency tests.