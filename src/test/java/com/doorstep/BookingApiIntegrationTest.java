package com.doorstep;

import static com.doorstep.ApiTestSupport.BATTERY;
import static com.doorstep.ApiTestSupport.DIAGNOSTICS;
import static com.doorstep.ApiTestSupport.JUMPSTART;
import static com.doorstep.ApiTestSupport.SOUTH;
import static com.doorstep.ApiTestSupport.body;
import static com.doorstep.ApiTestSupport.post;
import static com.doorstep.ApiTestSupport.slot;
import static org.assertj.core.api.Assertions.assertThat;

import com.doorstep.domain.Booking;
import com.doorstep.repo.BookingRepository;
import com.doorstep.repo.ServiceTypeRepository;
import com.doorstep.repo.TechnicianRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class BookingApiIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired BookingRepository bookings;
    @Autowired TechnicianRepository technicians;
    @Autowired ServiceTypeRepository services;

    @BeforeEach
    void clean() {
        bookings.deleteAll();
    }

    @Test
    void fullLifecycle_create_get_schedule_cancel_andSlotFreedUp() {
        LocalDateTime start = slot(2, 10, 0);

        // availability lists the slot before booking
        assertThat(slotsFor(BATTERY, SOUTH, start)).contains(start);

        ResponseEntity<Map> created = post(rest, "/bookings", body(BATTERY, SOUTH, start, "9876543210"), null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map booking = created.getBody();
        assertThat(booking.get("status")).isEqualTo("CONFIRMED");
        assertThat(LocalDateTime.parse((String) booking.get("endTime"))).isEqualTo(start.plusMinutes(60));
        Map tech = (Map) booking.get("technician");
        assertThat(tech.get("id")).isIn(1, 3); // the two BATTERY_FIT technicians in the south zone
        String id = (String) booking.get("id");

        // get by id
        ResponseEntity<Map> fetched = rest.getForEntity("/bookings/" + id, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("id")).isEqualTo(id);

        // schedule of the assigned technician contains it
        ResponseEntity<Map> schedule = rest.getForEntity(
                "/technicians/" + tech.get("id") + "/schedule?date=" + start.toLocalDate(), Map.class);
        assertThat(schedule.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>) schedule.getBody().get("bookings")).extracting(b -> b.get("id")).containsExactly(id);

        // cancel
        ResponseEntity<Map> cancelled = post(rest, "/bookings/" + id + "/cancel", null, null);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status")).isEqualTo("CANCELLED");

        // and it disappears from the schedule
        schedule = rest.getForEntity("/technicians/" + tech.get("id") + "/schedule?date=" + start.toLocalDate(), Map.class);
        assertThat((List<Map>) schedule.getBody().get("bookings")).isEmpty();

        // cancelling twice is a conflict
        assertThat(post(rest, "/bookings/" + id + "/cancel", null, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void availabilityDropsSlotOnceAllQualifiedTechniciansAreBooked_andReturnsAfterCancel() {
        LocalDateTime start = slot(2, 12, 0);
        String id1 = (String) post(rest, "/bookings", body(BATTERY, SOUTH, start, "9000000001"), null).getBody().get("id");
        post(rest, "/bookings", body(BATTERY, SOUTH, start, "9000000002"), null);

        // 12:00 and the 11:30 / 12:30 slots are blocked (overlap / travel buffer) for both technicians
        assertThat(slotsFor(BATTERY, SOUTH, start)).doesNotContain(
                start.minusMinutes(60), start.minusMinutes(30), start,
                start.plusMinutes(30), start.plusMinutes(60));
        // but a slot with enough gap is still offered
        assertThat(slotsFor(BATTERY, SOUTH, start)).contains(start.plusMinutes(120));

        ResponseEntity<Map> third = post(rest, "/bookings", body(BATTERY, SOUTH, start, "9000000003"), null);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(third.getBody().get("code")).isEqualTo("NO_AVAILABILITY");

        post(rest, "/bookings/" + id1 + "/cancel", null, null);
        assertThat(slotsFor(BATTERY, SOUTH, start)).contains(start);
        assertThat(post(rest, "/bookings", body(BATTERY, SOUTH, start, "9000000003"), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void assignsTechnicianWithFewestBookingsThatDay() {
        // Asha(1) gets a morning job; the next job in the afternoon should go to Chitra(3)
        LocalDateTime morning = slot(2, 9, 0);
        Map first = post(rest, "/bookings", body(BATTERY, SOUTH, morning, "9000000010"), null).getBody();
        Map second = post(rest, "/bookings", body(BATTERY, SOUTH, slot(2, 15, 0), "9000000011"), null).getBody();

        assertThat(((Map) first.get("technician")).get("id")).isEqualTo(1);   // tie -> lowest id
        assertThat(((Map) second.get("technician")).get("id")).isEqualTo(3);
    }

    @Test
    void zoneAndSkillAreEnforced() {
        // Bharat(2) has no BATTERY_FIT, so south battery jobs only ever go to Asha(1) or Chitra(3)
        LocalDateTime start = slot(2, 10, 0);
        for (int i = 0; i < 2; i++) {
            Map t = (Map) post(rest, "/bookings", body(BATTERY, SOUTH, start, "90000001" + i + "0"), null).getBody().get("technician");
            assertThat(t.get("id")).isIn(1, 3);
        }
        // a north pincode never gets a south technician
        Map north = (Map) post(rest, "/bookings", body(BATTERY, "560101", start, "9000000030"), null).getBody().get("technician");
        assertThat(north.get("id")).isIn(4, 6);
    }

    @Test
    void idempotencyKeyReturnsOriginalBooking() {
        LocalDateTime start = slot(3, 10, 0);
        Map body = body(JUMPSTART, SOUTH, start, "9000000040");

        ResponseEntity<Map> first = post(rest, "/bookings", body, "key-123");
        ResponseEntity<Map> retry = post(rest, "/bookings", body, "key-123");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(bookings.count()).isEqualTo(1);
    }

    @Test
    void validationErrorsUseConsistentBody() {
        LocalDateTime start = slot(2, 10, 0);

        ResponseEntity<Map> badPhone = post(rest, "/bookings", body(BATTERY, SOUTH, start, "abc"), null);
        assertThat(badPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badPhone.getBody()).containsKeys("timestamp", "status", "code", "message", "path");
        assertThat(badPhone.getBody().get("code")).isEqualTo("INVALID_REQUEST");

        assertThat(post(rest, "/bookings", body(BATTERY, SOUTH, slot(2, 10, 15), "9000000050"), null).getBody().get("code"))
                .isEqualTo("INVALID_SLOT");
        assertThat(post(rest, "/bookings", body(BATTERY, SOUTH, slot(30, 10, 0), "9000000050"), null).getBody().get("code"))
                .isEqualTo("OUTSIDE_BOOKING_WINDOW");
        assertThat(post(rest, "/bookings", body(BATTERY, SOUTH, slot(-1, 10, 0), "9000000050"), null).getBody().get("code"))
                .isEqualTo("OUTSIDE_BOOKING_WINDOW");
        assertThat(post(rest, "/bookings", body(BATTERY, "999999", start, "9000000050"), null).getBody().get("code"))
                .isEqualTo("PINCODE_NOT_SERVICEABLE");
        assertThat(post(rest, "/bookings", body(99, SOUTH, start, "9000000050"), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // outside shift (07:30 start) -> nobody can take it
        assertThat(post(rest, "/bookings", body(BATTERY, SOUTH, slot(2, 7, 30), "9000000050"), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // malformed JSON date
        assertThat(post(rest, "/bookings", Map.of("serviceId", 1, "pincode", SOUTH, "startTime", "tomorrow", "customerPhone", "9000000050"), null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/availability?serviceId=1&pincode=560001", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void notFoundCases() {
        assertThat(rest.getForEntity("/bookings/00000000-0000-0000-0000-000000000000", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(rest, "/bookings/00000000-0000-0000-0000-000000000000/cancel", null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/technicians/999/schedule?date=2026-11-12", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/bookings/not-a-uuid", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // unknown URLs are 404 with the standard error body, not 500
        ResponseEntity<Map> unknown = rest.getForEntity("/no-such-endpoint", Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void cancellingWithinOneHourOfStartIsRejected() {
        // The API cannot create such a booking (2h minimum lead), so insert one directly.
        LocalDateTime start = LocalDateTime.now(com.doorstep.config.AppConfig.IST).plusMinutes(30);
        Booking soon = bookings.save(new Booking("9000000060", services.findById(JUMPSTART).orElseThrow(), SOUTH,
                start, start.plusMinutes(30), technicians.findById(1L).orElseThrow(), null, start.minusHours(3)));

        ResponseEntity<Map> res = post(rest, "/bookings/" + soon.getId() + "/cancel", null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code")).isEqualTo("CANCEL_TOO_LATE");
        assertThat(bookings.findById(soon.getId()).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void listByPhoneIsPaginated() {
        for (int i = 0; i < 3; i++) {
            post(rest, "/bookings", body(DIAGNOSTICS, SOUTH, slot(2, 9 + 2 * i, 0), "9000000070"), null);
        }
        Map page = rest.getForEntity("/bookings?phone=9000000070&page=0&size=2", Map.class).getBody();
        assertThat((List) page.get("content")).hasSize(2);
        assertThat(page.get("totalElements")).isEqualTo(3);
        assertThat(page.get("totalPages")).isEqualTo(2);
    }

    @Test
    void healthEndpointIsUp() {
        assertThat(rest.getForEntity("/actuator/health", Map.class).getBody().get("status")).isEqualTo("UP");
    }

    private List<LocalDateTime> slotsFor(long serviceId, String pincode, LocalDateTime day) {
        Map res = rest.getForEntity("/availability?serviceId=" + serviceId + "&pincode=" + pincode
                + "&date=" + day.toLocalDate(), Map.class).getBody();
        return ((List<String>) res.get("slots")).stream().map(LocalDateTime::parse).toList();
    }
}
