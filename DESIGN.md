# Design Decisions and Scalability Considerations

## 1. Concurrency Handling

### How the current implementation works

The `POST /bookings` endpoint handles booking creation inside a single database transaction through `BookingCreator.create`.

The process follows these steps:

1. Identify all eligible technicians based on their service zone, required skills, and whether the entire job fits within their shift. No locks are acquired at this stage.
2. Execute `SELECT ... FOR UPDATE` on all eligible technician rows, ordering them by ID.
3. Once the locks are acquired, fetch the latest bookings for those technicians on the requested day and run `AssignmentPolicy.pick`. This checks availability, including the 30-minute buffer, and selects the technician with the fewest bookings that day. If there is a tie, the technician with the lowest ID is selected.
4. Create the booking and commit the transaction. All acquired locks are released after the transaction completes.

When multiple requests compete for the same technicians, they wait for the required database locks. Once the first transaction commits, the next request reads the updated booking data and makes its assignment decision accordingly. It either selects another available technician or returns `409 NO_AVAILABILITY`.

Locks are always acquired in ascending technician ID order. This gives concurrent requests a consistent locking sequence and prevents deadlocks caused by overlapping candidate sets.

### Why this approach?

I considered a few alternatives before settling on database-level pessimistic locking.

**1. Unique constraints**

A normal unique constraint cannot prevent overlapping booking intervals. For example, bookings starting at 10:00 and 10:30 have different start times but may still overlap.

PostgreSQL provides an exclusion constraint that can handle this:

```sql
EXCLUDE USING gist (
    technician_id WITH =,
    tsrange(start, end + buffer) WITH &&
)
WHERE status <> 'CANCELLED'
```

This would be a good additional safeguard at the database level. However, it rejects a conflicting booking only after a technician has already been selected. The application would then need a retry mechanism to try the next eligible technician.

I have not included it in the current implementation because it is PostgreSQL-specific, while the test suite also uses H2, and I wanted to keep a single source of truth for the assignment logic. It would be one of my first additions in a production PostgreSQL deployment.

**2. Optimistic locking**

Optimistic locking would require a version field, potentially on the technician record.

This can work under normal traffic, but during a busy period, such as a Monday morning, multiple requests may attempt to update the same technician version. Requests that lose the race would have to retry.

Since contention is exactly when these conflicts are most likely, pessimistic locking is a better fit for this booking workflow.

**3. Distributed locking with Redis**

Redis-based distributed locks would introduce another dependency and additional failure scenarios.

For example, a lock could expire while the database transaction is still running, or a failover could affect lock ownership. Either situation needs careful handling to avoid inconsistent assignments.

Since the application already uses a relational database for booking transactions, database locks provide a simpler and more reliable solution here.

**4. Serializable isolation**

Serializable isolation would provide strong consistency, but concurrent transactions could frequently encounter serialization failures and require retries. It would also make transaction behaviour more difficult to manage.

For this use case, explicitly locking the eligible technicians gives more control over the assignment process.

### Limitations and potential improvements

The current approach works well for the expected workload, but there are a few limitations to consider.

- **Lock contention:** Locking every eligible technician means bookings targeting the same zone and skill combination can become serialized. With approximately 10–100 technicians per zone and transactions taking only a few milliseconds, this should be manageable. However, a particularly busy zone could become a bottleneck. One option is to lock only the top-k candidates based on workload and expand the candidate set when necessary. Another option is to partition the workload by zone.

- **Database dependency:** This approach assumes that all booking operations use the same primary database. In a multi-region or sharded setup, the lock scope must remain within a single shard. All bookings belonging to a technician should be stored in the same shard as that technician.

- **Connection pool exhaustion:** Transactions waiting for locks continue to hold database connections. If lock waits become too long, a small connection pool could get exhausted. Setting a database `lock_timeout` and returning an appropriate `503 Service Unavailable` response would help manage this.

- **Idempotency key contention:** A unique database index enforces uniqueness for `Idempotency-Key`. If two requests race with the same key, the losing transaction may perform some unnecessary work before the conflict is detected. `BookingService.create` handles this by returning the booking created by the winning request.

---

## 2. Scaling the Application

### Expected workload

Consider the following growth scenario:

- 50 cities
- 5,000 technicians
- 200,000 bookings per day
- A 10x traffic spike on Mondays

At 200,000 bookings per day, the average write rate is approximately 2.3 requests per second. Even with a 10x increase and a concentrated morning peak, the expected write load would be around 100–200 requests per second.

A single PostgreSQL primary should be able to handle this workload with appropriate indexing and query optimization.

The bigger concern is likely to be read traffic, particularly availability checks, rather than booking creation.

### Scaling strategy

I would approach scaling incrementally rather than introducing distributed infrastructure before it is necessary.

**1. Optimize availability reads**

Availability checks are expected to account for most of the traffic. I would introduce caching and read replicas first. More details are covered in Section 3.

**2. Partition data by city**

Add a `city_id` column to the relevant tables so that data can be organized by city.

The existing index pattern, `(technician_id, start_time)`, already supports the booking lookup requirements.

As booking history grows, the `bookings` table can also be partitioned by month, with older records archived when they are no longer frequently accessed.

**3. Reduce lock contention**

Before moving to database sharding, I would first narrow the locking scope as described in Section 1.

Sharding by city would be considered only when a single PostgreSQL primary becomes a measurable bottleneck.

**4. Move notifications outside the request flow**

Notifications should not be sent directly while processing a booking request. They can be handled asynchronously using a transactional outbox, as described in Section 4.

**5. Introduce rate limiting**

Apply rate limits to `POST /bookings` at the API gateway, using customer phone numbers and IP addresses to prevent excessive requests.

### What I would monitor

Before making infrastructure changes, I would collect metrics to identify the actual bottlenecks.

Some important measurements include:

- **API latency:** p95 and p99 response times for each endpoint.
- **Database performance:** CPU utilization and connection pool wait times.
- **Lock contention:** Time spent waiting for technician row locks, using PostgreSQL views such as `pg_stat_activity` and `pg_locks`.
- **Booking conflicts:** Frequency of `409 NO_AVAILABILITY` responses. An increase could indicate that demand is exceeding available technician capacity rather than an application defect.
- **Read replica lag:** Delay between primary database writes and their availability on replicas.
- **Cache hit ratio:** Helps determine whether caching is reducing database load.
- **Slow queries:** Monitor the `findActive` query and identify opportunities for further optimization.

---

## 3. Improving Availability Performance

Availability checks are read-heavy operations, and the data they return can be treated as derived data rather than something that must always be recalculated for every request.

I would improve their performance using the following approach.

### Redis caching

Cache available slots in Redis using a key based on:

`(service, zone, date)`

A short TTL of around 5–15 seconds should be sufficient for most availability requests.

In addition to TTL-based expiry, I would introduce explicit cache invalidation. Whenever a booking is created or cancelled, the affected `(zone, date)` cache entries should be evicted. Alternatively, a version number can be maintained for each zone-date combination and incremented whenever availability changes.

### Handling stale availability

There is a possibility that the cache briefly displays a slot that has already been booked by another customer.

This is acceptable as long as the booking API remains the final authority.

For example:

1. Two customers view the same available slot.
2. The first customer successfully books it.
3. The second customer attempts to book the same slot.
4. The booking API checks the latest database state and returns `409 NO_AVAILABILITY`.
5. The client refreshes the availability information.

This is the safer failure scenario because the system never confirms a booking that it cannot fulfil.

Explicit invalidation on booking creation and cancellation reduces the stale-data window to approximately milliseconds under normal conditions.

### Further optimizations

- **Reduce database queries:** Calculate availability in memory using a single query to fetch active bookings for all eligible technicians. The current implementation already follows this approach through one `findActive` call rather than querying separately for every slot.
- **Read replicas:** Serve availability requests from read replicas to reduce the load on the primary database. The booking creation path must continue checking the authoritative database.
- **Precompute availability:** Calculate available slots by `(zone, skill, date)` rather than separately for every pincode. This avoids repeating the same calculations for pincodes belonging to the same zone.
- **Improve the UI:** Optionally return the number of free technicians for each slot. After refreshing availability, the UI can hide slots where the count is zero.

---

## 4. Handling Notifications

Notifications should be processed independently of the booking request so that external provider issues do not affect booking creation.

I would use the **Transactional Outbox Pattern** for this.

### How it works

When a booking is created, the application inserts both the booking record and a notification event into an `outbox` table within the same database transaction.

The outbox record would contain fields such as:

```text
event_type
payload
status
```

Since both inserts happen in the same transaction, either both are committed or neither is committed.

A separate background worker processes the pending outbox events.

The worker can either poll the outbox table or consume events through a CDC pipeline using Debezium and Kafka/SQS.

### Notification processing

The worker is responsible for:

1. Reading pending notification events.
2. Sending notifications through SMS, WhatsApp, or push notification providers.
3. Retrying failed deliveries using exponential backoff.
4. Moving repeatedly failed events into a dead-letter queue.
5. Using a circuit breaker for each notification provider to avoid repeatedly calling an unavailable service.

Notification delivery would follow an at-least-once model. To prevent duplicate messages, the booking ID can be used as a deduplication key.

If WhatsApp delivery fails, the worker can fall back to SMS.

### Why use this approach?

The booking API does not directly depend on external notification providers.

This means a slow or unavailable notification service cannot delay or fail an otherwise valid booking request.

It also ensures that a booking cannot be committed without its corresponding notification event being recorded in the database.

---

## 5. Reassigning Technicians in Case of Unavailability

Consider a situation where a technician becomes unavailable due to sickness or an unexpected absence after receiving bookings.

### What the current design already supports

The current data model makes reassignment relatively straightforward.

- A booking stores the assigned technician through a foreign key.
- Assignment rules are centralized in the standalone `AssignmentPolicy` class.
- Booking start and end times are explicitly stored in the database through the existing `V1__schema` migration.

To reassign a booking, the system can exclude the unavailable technician from the candidate list and call:

```java
pick(candidatesMinusUnavailableTechnician, ...)
```

This can be done for each of the affected technician's bookings on that day, inside a transaction that locks all eligible candidates.

Once a replacement is selected, reassignment simply involves updating the booking's `technician_id`.

### What would need to be added

There are a few areas that the current implementation does not cover yet.

**1. Technician leave and unavailability**

Technician shifts are currently fixed. There is no way to represent sick leave, planned leave, or temporary unavailability.

I would introduce a table such as:

```text
technician_unavailability
-------------------------
technician_id
from
to
```

The `AssignmentPolicy.withinShift` check would also consider these unavailable periods before assigning a technician.

**2. Reassignment history**

The current schema does not record who a booking was reassigned from or why the change was made.

I would introduce a `booking_assignment_history` table to maintain an audit trail of assignment changes.

**3. Handling cases where no replacement is available**

It may not always be possible to reassign every booking.

In such cases, the system should support an `UNASSIGNED` or `NEEDS_ATTENTION` state and notify the operations team and affected customers.

**4. Reassignment priority**

Bookings should be processed based on their scheduled start times, prioritizing the jobs that are due soonest.

**5. Batch processing**

The fewest-bookings assignment rule can change as jobs are reassigned. Therefore, the affected bookings should be processed as a single batch rather than as completely independent operations.

---

## 6. What I Would Implement Next

With another week of development time, I would prioritize the following improvements:

1. **Database-level booking protection:** Add the PostgreSQL exclusion constraint to provide an additional guarantee against overlapping bookings. Also, introduce a Testcontainers-based concurrency test against real PostgreSQL, since H2 only approximates PostgreSQL's locking behaviour.

2. **Notifications:** Implement the transactional outbox and background notification worker described in Section 4. Also, add a `COMPLETED` state transition for bookings.

3. **Technician availability management:** Add a technician leave and unavailability model, along with an endpoint for reassignment.

4. **Idempotency improvements:** Store a request hash alongside each idempotency key. If the same key is received with a different request body, return `422 Unprocessable Entity` instead of silently returning the original booking. Also, introduce an expiry policy for idempotency keys.

5. **Production readiness:** Add rate limiting, application metrics using Micrometer and Prometheus, and authentication. (API documentation is already served through springdoc/OpenAPI at `/swagger-ui.html`.)

These improvements would strengthen the existing booking workflow while making the application easier to operate and scale as the number of cities, technicians, and bookings increases.