# API Testing Guide

This document provides step-by-step instructions for manually testing all the API endpoints, along with the expected responses and important edge cases.

Before running the tests, make sure the application is up and running.

```bash
docker compose up --build
```

**Base URL:** `http://localhost:8080`

All application times are in IST. When sending date-time values, use the local date-time format without a timezone offset.

Example: `2026-11-12T10:00:00`

## Seed Data

The application is initialized with the following services, zones, pincodes, and technicians.

### Available Services

| Service ID | Service Name | Duration (Minutes) | Required Skill |
|---:|---|---:|---|
| 1 | Battery replacement | 60 | BATTERY_FIT |
| 2 | Jump-start | 30 | JUMPSTART |
| 3 | Battery health check | 30 | DIAGNOSTICS |

### Zones and Technicians

| Zone | Serviceable Pincodes | Technicians and Skills |
|---|---|---|
| 1 | 560001, 560002, 560003 | Asha (ID: 1 – all 3 skills), Bharat (ID: 2 – JUMPSTART, DIAGNOSTICS), Chitra (ID: 3 – BATTERY_FIT) |
| 2 | 560101, 560102, 560103 | Dev (ID: 4 – all 3 skills), Esha (ID: 5 – JUMPSTART), Farid (ID: 6 – BATTERY_FIT, DIAGNOSTICS) |

For pincode `560001`, the battery replacement service has exactly two qualified technicians: Asha and Chitra.

This makes it a useful test case for checking how the application handles the last available technician.

---

## 0. Setup

### Bash / Git Bash / WSL

Set the base URL and generate a date two days ahead.

```bash
BASE=http://localhost:8080
D=$(date -d "+2 day" +%F)  # macOS: D=$(date -v+2d +%F)

echo $D
```

### PowerShell

```powershell
$BASE = "http://localhost:8080"
$D = (Get-Date).AddDays(2).ToString("yyyy-MM-dd")
```

The variable `D` will be used throughout the examples.

---

## 1. Application Health Check

Check whether the application is running:

```bash
curl -s $BASE/actuator/health
```

**Expected response:**

- HTTP status: `200 OK`

```json
{
  "status": "UP"
}
```

---

## 2. Check Availability

**Endpoint:** `GET /availability`

Check the available booking slots for a particular service, pincode, and date.

```bash
curl -s "$BASE/availability?serviceId=1&pincode=560001&date=$D"
```

**Expected response:**

- HTTP status: `200 OK`
- Available slots should range from `08:00:00` to `19:00:00`.
- Slots are generated in 30-minute intervals.

Since battery replacement takes 60 minutes, the last available starting time is 19:00. This ensures that the job finishes by the end of the technician's shift at 20:00.

### Additional test cases

| Scenario | Request / Parameter | Expected Result |
|---|---|---|
| Jump-start service (30 minutes) | `serviceId=2` | Last available slot: `19:30:00` |
| Unknown service | `serviceId=99` | `404 NOT_FOUND` |
| Unserviceable pincode | `pincode=999999` | `422 PINCODE_NOT_SERVICEABLE` |
| Missing date | Omit `date` | `400 INVALID_REQUEST` |
| Past date | `date=2020-01-01` | `200 OK` with empty `slots` |
| More than 7 days ahead | Date beyond the booking window | `200 OK` with empty `slots` |

---

## 3. Create a Booking

**Endpoint:** `POST /bookings`

Create a booking for battery replacement using the following request.

```bash
curl -s -i -X POST $BASE/bookings \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" \
  -d "{\"serviceId\":1,\"pincode\":\"560001\",\"startTime\":\"${D}T10:00:00\",\"customerPhone\":\"9876543210\"}"
```

**Expected response:**

- HTTP status: `201 Created`
- A new booking should be created and assigned to an eligible technician.

Example response:

```json
{
  "id": "7c0f0c0e-....",
  "customerPhone": "9876543210",
  "service": {
    "id": 1,
    "name": "Battery replacement",
    "durationMinutes": 60
  },
  "pincode": "560001",
  "startTime": "2026-11-11T10:00:00",
  "endTime": "2026-11-11T11:00:00",
  "technician": {
    "id": 1,
    "name": "Asha"
  },
  "status": "CONFIRMED",
  "createdAt": "..."
}
```

Save the returned booking ID. It will be required for the subsequent tests.

```bash
ID=<paste-booking-id-here>
```

### 3a. Verify Idempotency

Run the exact same booking request again, using the same `Idempotency-Key`.

**Expected behaviour:**

- HTTP status: `200 OK`, instead of `201 Created`.
- The returned booking ID should be the same as the original.
- No additional booking should be created.

Verify this using:

```bash
curl -s "$BASE/bookings?phone=9876543210"
```

The response should contain only one booking for that phone number.

### 3b. Verify Technician Assignment

This test checks the technician selection rule: fewest bookings for the day, followed by the lowest technician ID in case of a tie.

Create another battery replacement booking for the same pincode on the same day, but at a different time.

For example, use `15:00` and a different customer phone number.

**Expected behaviour:**

- Asha already has one booking that day.
- Chitra has no bookings.
- The new booking should be assigned to Chitra (technician ID: 3).

### 3c. Verify No Availability

This test checks whether the application prevents more bookings than the available technician capacity.

For pincode `560001`, both Asha and Chitra are qualified for battery replacement.

At `10:00`, create two additional booking requests using different customer phone numbers.

Assuming the initial booking is assigned to Asha:

- The first additional request should be assigned to Chitra.
- The next request should fail because both qualified technicians are already occupied.

**Expected response for the final request:**

- HTTP status: `409 Conflict`

```json
{
  "status": 409,
  "code": "NO_AVAILABILITY",
  "message": "No technician is available for the requested slot",
  "...": "..."
}
```

### 3d. Verify the 30-Minute Travel Buffer

Once both Asha and Chitra have bookings from `10:00` to `11:00`, check the availability endpoint again.

The system should enforce the 30-minute buffer before and after each job.

For battery replacement, the following starting times should not be available:

- `09:00` – the job would finish exactly at 10:00, leaving no buffer.
- `09:30`
- `10:00`
- `10:30`
- `11:00` – the next job would start without the required buffer.

The following slots should still be available:

- `08:00`
- `08:30`
- `11:30`

The `11:30` slot becomes available because it leaves exactly 30 minutes after the previous job ends at 11:00.

**Note:** These results assume both qualified technicians are occupied during the same 10:00–11:00 window, as established in the previous test.

### 3e. Verify Validation and Business Rule Errors

The following cases check how the API handles invalid input and requests that violate booking rules.

| Test Case | Change to Request Body | Expected Result |
|---|---|---|
| Invalid phone number | `customerPhone: "abc"` | `400 INVALID_REQUEST` with `details` |
| Invalid slot interval | `startTime: "10:15:00"` | `422 INVALID_SLOT` |
| Booking too soon | `startTime` less than 2 hours from now | `422 OUTSIDE_BOOKING_WINDOW` |
| Booking too far ahead | `startTime` more than 7 days ahead | `422 OUTSIDE_BOOKING_WINDOW` |
| Before technician shift | `startTime: "07:30:00"` for battery replacement | `409 NO_AVAILABILITY` |
| Job exceeds technician shift | `startTime: "19:30:00"` for battery replacement | `409 NO_AVAILABILITY` |
| Unserviceable pincode | `pincode: "999999"` | `422 PINCODE_NOT_SERVICEABLE` |
| Unknown service | `serviceId: 99` | `404 NOT_FOUND` |
| Invalid date-time format | `startTime: "tomorrow"` | `400 INVALID_REQUEST` |
| Empty request body | No body | `400 INVALID_REQUEST` |

All API errors follow the same response structure:

```json
{
  "timestamp": "...",
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "...",
  "path": "...",
  "details": "..."
}
```

The `details` field is optional.

---

## 4. Retrieve Booking Details

**Endpoint:** `GET /bookings/{id}`

Use the booking ID saved during the booking creation test.

```bash
curl -s $BASE/bookings/$ID
```

**Expected behaviour:**

- HTTP status: `200 OK`.
- The response should contain the booking details, including the assigned technician, service, start time, end time, and booking status.

Additional cases:

| Scenario | Expected Result |
|---|---|
| Valid booking UUID | `200 OK` |
| Unknown but valid UUID | `404 NOT_FOUND` |
| Invalid UUID format | `400 INVALID_REQUEST` |

---

## 5. Cancel a Booking

**Endpoint:** `POST /bookings/{id}/cancel`

Cancel the booking created earlier.

```bash
curl -s -X POST $BASE/bookings/$ID/cancel
```

**Expected behaviour:**

- HTTP status: `200 OK`.
- Booking status should change to `CANCELLED`.

### Verify slot availability after cancellation

Once the booking is cancelled, check availability for the same service, pincode, date, and time.

The previously occupied slot should become available again immediately, provided another eligible technician is not occupying it.

### Additional cancellation scenarios

| Scenario | Expected Result |
|---|---|
| Cancel a confirmed booking within the allowed cutoff | `200 OK` |
| Cancel the same booking again | `409 INVALID_STATE` |
| Cancel a booking starting in less than one hour | `409 CANCEL_TOO_LATE` |

**Note:** The booking API does not allow creating bookings less than two hours in advance. Therefore, the cancellation cutoff scenario is tested through the automated test `cancellingWithinOneHourOfStartIsRejected`, which inserts a booking directly into the test database.

---

## 6. View Technician Schedule

**Endpoint:** `GET /technicians/{id}/schedule`

Retrieve the bookings assigned to a particular technician for a given date.

```bash
curl -s "$BASE/technicians/1/schedule?date=$D"
```

**Expected behaviour:**

- HTTP status: `200 OK`.
- The response should contain the technician ID, date, and assigned bookings.
- Bookings should be ordered by their start time.
- Cancelled bookings should not appear in the schedule.

Additional case:

| Scenario | Expected Result |
|---|---|
| Valid technician ID | `200 OK` |
| Unknown technician ID | `404 NOT_FOUND` |

---

## 7. List Bookings by Customer Phone

**Endpoint:** `GET /bookings?phone=`

Retrieve all bookings associated with a customer phone number.

```bash
curl -s "$BASE/bookings?phone=9876543210&page=0&size=10"
```

**Expected response structure:**

```json
{
  "content": [],
  "page": 0,
  "size": 10,
  "totalElements": 0,
  "totalPages": 0
}
```

The response contains:

- `content`: List of matching bookings.
- `page`: Current page number.
- `size`: Number of records requested per page.
- `totalElements`: Total matching records.
- `totalPages`: Total number of available pages.

The page size must be between 1 and 100.

---

## 8. Test Concurrent Bookings

This test verifies that multiple requests attempting to book the same slot cannot assign the same technician more than once.

### Setup

For pincode `560001`, Asha and Chitra are the only two technicians qualified for battery replacement.

First, create one battery replacement booking at `13:00` to occupy one of them.

This leaves only one technician available for that time slot.

### Run 10 parallel requests

Execute the following script using Bash, Git Bash, or WSL:

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST $BASE/bookings \
    -H "Content-Type: application/json" \
    -d "{\"serviceId\":1,\"pincode\":\"560001\",\"startTime\":\"${D}T13:00:00\",\"customerPhone\":\"91000000$i$i\"}" &
done
wait
```

All 10 requests attempt to book the same slot simultaneously.

**Expected result:**

| HTTP Status | Expected Count | Meaning |
|---|---:|---|
| `201` | 1 | One booking is successfully created. |
| `409` | 9 | Remaining requests are rejected because no technician is available. |

This verifies that the application correctly handles simultaneous booking requests without double-booking the last available technician.

### Automated concurrency test

The same scenario is covered by `ConcurrentBookingTest`.

For the technical discussion or live demonstration, run:

```bash
mvn test -Dtest=ConcurrentBookingTest
```

This test also covers overlapping bookings with different start times, parallel retries using the same `Idempotency-Key`, and database consistency after concurrent requests.

---

## 9. Automated Test Suite

In addition to the manual API checks, the application includes automated tests for business rules, API workflows, and concurrent booking scenarios.

Run the complete test suite:

```bash
mvn test
```

Or run individual test classes:

### Assignment Policy Tests

```bash
mvn test -Dtest=AssignmentPolicyTest
```

Covers:

- Booking overlap checks.
- 30-minute buffer rules.
- Technician shift boundaries.
- Skill and service-zone eligibility.
- Slot boundaries.
- Booking lead-time restrictions.
- Cancellation cutoff.
- Technician selection based on the fewest bookings.
- Tie-breaking using technician ID.

### API Integration Tests

```bash
mvn test -Dtest=BookingApiIntegrationTest
```

Covers:

- Complete booking API workflows.
- Idempotency behaviour.
- HTTP status codes and error responses.
- Cancellation and slot availability.
- Pagination.
- Application health checks.

### Concurrent Booking Tests

```bash
mvn test -Dtest=ConcurrentBookingTest
```

Covers:

- Parallel booking requests.
- Concurrent requests competing for the same technician.
- Overlapping bookings with different start times.
- Parallel retries using the same idempotency key.
- Database consistency after concurrent operations.

All automated tests use an in-memory H2 database configured in PostgreSQL compatibility mode, so Docker is not required to run the test suite.