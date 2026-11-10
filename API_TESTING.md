# API Testing Guide

Manual checks for every endpoint, with expected results. Each step has a **Bash** version (Linux, macOS, Git Bash, WSL) and a **PowerShell** version (Windows). To test in the browser instead, open Swagger UI at `http://localhost:8080/swagger-ui.html`.

Start the app first (see [README](README.md#run-the-application)):

```sh
docker compose up --build
```

All times are IST local date-times without offset, for example `2026-11-12T10:00:00`. Run the steps in order, because later steps depend on bookings made earlier. To start over, restart the app (H2) or run `docker compose down` and then start it again (PostgreSQL).

## Seed Data

| Service ID | Name | Minutes | Skill |
|---:|---|---:|---|
| 1 | Battery replacement | 60 | BATTERY_FIT |
| 2 | Jump-start | 30 | JUMPSTART |
| 3 | Battery health check | 30 | DIAGNOSTICS |

| Zone | Pincodes | Technicians (ID: skills) |
|---|---|---|
| 1 | 560001, 560002, 560003 | Asha (1: all), Bharat (2: JUMPSTART, DIAGNOSTICS), Chitra (3: BATTERY_FIT) |
| 2 | 560101, 560102, 560103 | Dev (4: all), Esha (5: JUMPSTART), Farid (6: BATTERY_FIT, DIAGNOSTICS) |

All shifts are 08:00–20:00. For pincode `560001`, battery replacement has exactly two qualified technicians, Asha and Chitra. Most tests below use that pair.

## 0. Setup

Set the base URL, a date two days ahead, and a `book` helper that posts a battery-replacement booking for `560001` at a given time.

Bash:

```sh
BASE=http://localhost:8080
D=$(date -d "+2 day" +%F)          # macOS: D=$(date -v+2d +%F)

book() {  # usage: book HH:MM:SS PHONE [IDEMPOTENCY_KEY]
  curl -s -w "\n%{http_code}\n" -X POST "$BASE/bookings" \
    -H "Content-Type: application/json" ${3:+-H "Idempotency-Key: $3"} \
    -d "{\"serviceId\":1,\"pincode\":\"560001\",\"startTime\":\"${D}T$1\",\"customerPhone\":\"$2\"}"
}
```

PowerShell:

```powershell
$BASE = "http://localhost:8080"
$D = (Get-Date).AddDays(2).ToString("yyyy-MM-dd")

function book($Time, $Phone, $Key) {  # usage: book HH:MM:SS PHONE [IDEMPOTENCY_KEY]
  $extra = if ($Key) { "-H", "Idempotency-Key: $Key" } else { @() }
  @{ serviceId = 1; pincode = "560001"; startTime = "${D}T$Time"; customerPhone = $Phone } | ConvertTo-Json -Compress |
    curl.exe -s -w "`n%{http_code}`n" -X POST "$BASE/bookings" -H "Content-Type: application/json" @extra --data-binary "@-"
}
```

`book` prints the response body and then the HTTP status. PowerShell uses `curl.exe` (built into Windows 10 and later). Plain `curl` in Windows PowerShell is an alias for `Invoke-WebRequest`. The JSON body is piped in on stdin because Windows PowerShell 5.1 strips the quotes from JSON passed as an argument.

## 1. Health

```sh
curl -s "$BASE/actuator/health"        # Bash
curl.exe -s "$BASE/actuator/health"    # PowerShell
```

Expected: `200`, `{"status":"UP"}`.

## 2. Availability

```sh
curl -s "$BASE/availability?serviceId=1&pincode=560001&date=$D"        # Bash
curl.exe -s "$BASE/availability?serviceId=1&pincode=560001&date=$D"    # PowerShell
```

Expected: `200`, with 23 slots from `08:00:00` to `19:00:00` every 30 minutes. A 60-minute job starting at 19:00 ends at the 20:00 shift end.

| Change | Expected |
|---|---|
| `serviceId=2` (30 min) | last slot `19:30:00` |
| `serviceId=99` | `404 NOT_FOUND` |
| `pincode=999999` | `422 PINCODE_NOT_SERVICEABLE` |
| no `date` | `400 INVALID_REQUEST` |
| `date=2020-01-01` or more than 7 days ahead | `200` with empty `slots` |

## 3. Create a Booking

Bash:

```sh
res=$(book 10:00:00 9876543210 demo-1); echo "$res"
ID=$(echo "$res" | head -1 | sed -E 's/.*"id":"([^"]+)".*/\1/')
```

PowerShell:

```powershell
$res = book 10:00:00 9876543210 demo-1; $res
$ID = ($res[0] | ConvertFrom-Json).id
```

Expected: `201`, status `CONFIRMED`, assigned to Asha (ID 1, the lowest ID when both have no bookings):

```json
{
  "id": "5a38d27a-2198-4d2f-9bbe-c481eae668ee",
  "customerPhone": "9876543210",
  "service": { "id": 1, "name": "Battery replacement", "durationMinutes": 60 },
  "pincode": "560001",
  "startTime": "2026-11-12T10:00:00",
  "endTime": "2026-11-12T11:00:00",
  "technician": { "id": 1, "name": "Asha" },
  "status": "CONFIRMED",
  "createdAt": "2026-11-10T23:36:54.95"
}
```

### 3a. Idempotency

Run `book 10:00:00 9876543210 demo-1` again.

Expected: `200` (not `201`) with the same `id`. `GET /bookings?phone=9876543210` still returns one booking.

### 3b. Fewest-bookings assignment

```sh
book 15:00:00 9876500001
```

Expected: `201`, assigned to Chitra (ID 3), because Asha already has a booking that day and Chitra has none.

### 3c. No availability

```sh
book 10:00:00 9876500002     # 201, Chitra takes the 10:00 slot
book 10:00:00 9876500003     # 409 NO_AVAILABILITY, both technicians busy
```

### 3d. 30-minute buffer

With Asha and Chitra both booked 10:00–11:00, run the availability request from step 2 again. Between 08:00 and 11:30:

- Available: `08:00`, `08:30`, `11:30`
- Not available: `09:00` (ends at 10:00 with no buffer), `09:30`, `10:00`, `10:30`, `11:00` (only 0 minutes after the 11:00 end)

### 3e. Validation and rule errors

| Request | Expected |
|---|---|
| `book 10:00:00 abc` | `400 INVALID_REQUEST`, `details` lists the field |
| `book 10:15:00 9876543210` | `422 INVALID_SLOT` |
| `book 07:30:00 9876543210` (before shift) | `409 NO_AVAILABILITY` |
| `book 19:30:00 9876543210` (ends after shift) | `409 NO_AVAILABILITY` |
| `startTime` less than 2 hours or more than 7 days away | `422 OUTSIDE_BOOKING_WINDOW` |
| body with `"pincode":"999999"` | `422 PINCODE_NOT_SERVICEABLE` |
| body with `"serviceId":99` | `404 NOT_FOUND` |
| body with `"startTime":"tomorrow"`, or no body | `400 INVALID_REQUEST` |

## 4. Get a Booking

```sh
curl -s "$BASE/bookings/$ID"           # Bash
curl.exe -s "$BASE/bookings/$ID"       # PowerShell
```

| Case | Expected |
|---|---|
| existing id | `200` |
| `00000000-0000-0000-0000-000000000000` | `404 NOT_FOUND` |
| `not-a-uuid` | `400 INVALID_REQUEST` |

## 5. Cancel a Booking

```sh
curl -s -X POST "$BASE/bookings/$ID/cancel"        # Bash
curl.exe -s -X POST "$BASE/bookings/$ID/cancel"    # PowerShell
```

Expected: `200`, status `CANCELLED`. The 10:00 slot appears in availability again because Asha is now free.

| Case | Expected |
|---|---|
| cancel again | `409 INVALID_STATE` |
| less than 1 hour before start | `409 CANCEL_TOO_LATE` |

Bookings must be made at least 2 hours ahead, so the `CANCEL_TOO_LATE` case can't be reached through the API right away. The automated test `cancellingWithinOneHourOfStartIsRejected` covers it.

## 6. Technician Schedule

```sh
curl -s "$BASE/technicians/3/schedule?date=$D"        # Bash
curl.exe -s "$BASE/technicians/3/schedule?date=$D"    # PowerShell
```

Expected: `200`, Chitra's non-cancelled bookings ordered by start time (10:00, 15:00). An unknown technician ID returns `404 NOT_FOUND`.

## 7. Bookings by Phone

```sh
curl -s "$BASE/bookings?phone=9876543210&page=0&size=10"        # Bash
curl.exe -s "$BASE/bookings?phone=9876543210&page=0&size=10"    # PowerShell
```

Expected: `200` with `content`, `page`, `size`, `totalElements` and `totalPages`. Bookings are newest first. `size` must be 1–100 (default 20). Anything else returns `400`.

## 8. Concurrent Bookings

Take one of the two technicians at 13:00, then fire 10 parallel requests for the same slot. Only one technician is left, so exactly one request may succeed.

Bash:

```sh
book 13:00:00 9100000000
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/bookings" \
    -H "Content-Type: application/json" \
    -d "{\"serviceId\":1,\"pincode\":\"560001\",\"startTime\":\"${D}T13:00:00\",\"customerPhone\":\"9100000$(printf %03d $i)\"}" &
done; wait
```

PowerShell (works in Windows PowerShell 5.1 and PowerShell 7):

```powershell
book 13:00:00 9100000000
$jobs = 1..10 | ForEach-Object {
  Start-Job -ArgumentList $BASE, $D, $_ -ScriptBlock {
    param($Base, $Date, $i)
    $body = @{ serviceId = 1; pincode = "560001"; startTime = "${Date}T13:00:00"; customerPhone = "9100000{0:D3}" -f $i } | ConvertTo-Json
    try { (Invoke-WebRequest -Method Post -Uri "$Base/bookings" -ContentType "application/json" -Body $body -UseBasicParsing).StatusCode }
    catch { [int]$_.Exception.Response.StatusCode }
  }
}
$jobs | Wait-Job | Receive-Job; $jobs | Remove-Job
```

Expected: one `201` and nine `409`.

PowerShell jobs start slowly, so these requests overlap less than the Bash ones. `ConcurrentBookingTest` runs the race with truly parallel requests and also checks the database afterwards:

```sh
./mvnw test -Dtest=ConcurrentBookingTest      # Linux/macOS
.\mvnw test "-Dtest=ConcurrentBookingTest"    # Windows
```

## 9. Automated Tests

```sh
./mvnw test          # Linux/macOS
.\mvnw test          # Windows
```

Run a single class with `-Dtest=AssignmentPolicyTest`, `BookingApiIntegrationTest` or `ConcurrentBookingTest`. See the [README](README.md#running-tests) for what each class covers. Tests use in-memory H2, so Docker is not needed.
