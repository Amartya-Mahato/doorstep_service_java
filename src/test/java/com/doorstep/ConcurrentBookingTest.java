package com.doorstep;

import static com.doorstep.ApiTestSupport.BATTERY;
import static com.doorstep.ApiTestSupport.SOUTH;
import static com.doorstep.ApiTestSupport.body;
import static com.doorstep.ApiTestSupport.post;
import static com.doorstep.ApiTestSupport.slot;
import static org.assertj.core.api.Assertions.assertThat;

import com.doorstep.domain.AssignmentPolicy;
import com.doorstep.domain.Booking;
import com.doorstep.domain.BookingStatus;
import com.doorstep.repo.BookingRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Real HTTP requests from parallel threads against the real service and database.
 * South zone has exactly two BATTERY_FIT technicians (Asha, Chitra), so a battery slot has capacity 2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("rawtypes")
class ConcurrentBookingTest {

    @Autowired TestRestTemplate rest;
    @Autowired BookingRepository bookings;

    @BeforeEach
    void clean() {
        bookings.deleteAll();
    }

    @Test
    void racingForTheLastFreeTechnician_exactlyOneWins() throws Exception {
        LocalDateTime start = slot(2, 10, 0);
        // Use up one of the two technicians first, leaving exactly one free.
        assertThat(post(rest, "/bookings", body(BATTERY, SOUTH, start, "9100000000"), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        List<ResponseEntity<Map>> results = raceBookings(start, 10);

        assertThat(count(results, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(count(results, HttpStatus.CONFLICT)).isEqualTo(9);
        results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertThat(r.getBody().get("code")).isEqualTo("NO_AVAILABILITY"));
        assertThat(bookings.count()).isEqualTo(2);
        assertNoTechnicianIsDoubleBooked();
    }

    @Test
    void manyRequestsForOneSlot_successesNeverExceedQualifiedTechnicians() throws Exception {
        LocalDateTime start = slot(2, 14, 0);

        List<ResponseEntity<Map>> results = raceBookings(start, 12);

        assertThat(count(results, HttpStatus.CREATED)).isEqualTo(2);
        assertThat(count(results, HttpStatus.CONFLICT)).isEqualTo(10);
        assertThat(bookings.count()).isEqualTo(2);
        assertNoTechnicianIsDoubleBooked();
    }

    @Test
    void overlappingButDifferentStartTimesAlsoCannotDoubleBook() throws Exception {
        // 10:00, 10:30, 11:00 all overlap/touch within the 30-min buffer for a 60-min job; only 2 technicians exist.
        LocalDateTime base = slot(3, 10, 0);
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            LocalDateTime start = base.plusMinutes(30L * (i % 3));
            String phone = "92000000" + String.format("%02d", i);
            futures.add(pool.submit(() -> {
                go.await();
                return post(rest, "/bookings", body(BATTERY, SOUTH, start, phone), null);
            }));
        }
        go.countDown();
        List<ResponseEntity<Map>> results = collect(futures);
        pool.shutdown();

        assertThat(count(results, HttpStatus.CREATED)).isEqualTo(2);
        assertNoTechnicianIsDoubleBooked();
    }

    @Test
    void parallelRetriesWithSameIdempotencyKeyCreateExactlyOneBooking() throws Exception {
        LocalDateTime start = slot(2, 16, 0);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return post(rest, "/bookings", body(BATTERY, SOUTH, start, "9300000000"), "same-key-1");
            }));
        }
        go.countDown();
        List<ResponseEntity<Map>> results = collect(futures);
        pool.shutdown();

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful()).isTrue());
        assertThat(results.stream().map(r -> r.getBody().get("id")).distinct()).hasSize(1);
        assertThat(count(results, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(bookings.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------

    private List<ResponseEntity<Map>> raceBookings(LocalDateTime start, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String phone = "91000001" + String.format("%02d", i);
            Callable<ResponseEntity<Map>> task = () -> {
                ready.countDown();
                go.await();
                return post(rest, "/bookings", body(BATTERY, SOUTH, start, phone), null);
            };
            futures.add(pool.submit(task));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<ResponseEntity<Map>> results = collect(futures);
        pool.shutdown();
        return results;
    }

    private List<ResponseEntity<Map>> collect(List<Future<ResponseEntity<Map>>> futures) throws Exception {
        List<ResponseEntity<Map>> out = new ArrayList<>();
        for (Future<ResponseEntity<Map>> f : futures) {
            out.add(f.get(60, TimeUnit.SECONDS));
        }
        return out;
    }

    private long count(List<ResponseEntity<Map>> results, HttpStatus status) {
        return results.stream().filter(r -> r.getStatusCode() == status).count();
    }

    /** The invariant itself, checked straight from the database: no two live bookings of a technician conflict. */
    private void assertNoTechnicianIsDoubleBooked() {
        List<Booking> live = bookings.findAll().stream().filter(b -> b.getStatus() != BookingStatus.CANCELLED).toList();
        for (int i = 0; i < live.size(); i++) {
            for (int j = i + 1; j < live.size(); j++) {
                Booking a = live.get(i);
                Booking b = live.get(j);
                if (a.getTechnician().getId().equals(b.getTechnician().getId())) {
                    assertThat(AssignmentPolicy.conflicts(a.getStartTime(), a.getEndTime(), b.getStartTime(), b.getEndTime()))
                            .as("technician %s has conflicting bookings %s and %s", a.getTechnician().getName(),
                                    a.getStartTime(), b.getStartTime())
                            .isFalse();
                }
            }
        }
    }
}
