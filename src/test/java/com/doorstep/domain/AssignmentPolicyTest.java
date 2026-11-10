package com.doorstep.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AssignmentPolicyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    private final Zone south = new Zone(1L, "South", Set.of("560001"));
    private final Zone north = new Zone(2L, "North", Set.of("560101"));
    private final ServiceType battery = new ServiceType(1L, "Battery replacement", 60, "BATTERY_FIT");
    private final ServiceType jump = new ServiceType(2L, "Jump-start", 30, "JUMPSTART");

    private Technician tech(long id, Zone zone, String... skills) {
        return new Technician(id, "T" + id, zone, Set.of(skills), LocalTime.of(8, 0), LocalTime.of(20, 0));
    }

    private LocalDateTime at(int h, int m) {
        return DAY.atTime(h, m);
    }

    private Booking booking(Technician t, ServiceType s, int h, int m) {
        LocalDateTime start = at(h, m);
        return new Booking("9999999999", s, "560001", start, start.plusMinutes(s.getDurationMinutes()), t, null, at(0, 0));
    }

    // ---- overlap + buffer (rules 3, 5) -------------------------------------------------------------

    @Test
    void sameSlotConflicts() {
        assertThat(AssignmentPolicy.conflicts(at(10, 0), at(11, 0), at(10, 0), at(11, 0))).isTrue();
    }

    @Test
    void partialOverlapConflicts() {
        assertThat(AssignmentPolicy.conflicts(at(10, 30), at(11, 30), at(10, 0), at(11, 0))).isTrue();
    }

    @Test
    void startingInsideBufferAfterJobConflicts() {
        // existing 10:00-11:00 occupies the technician until 11:30
        assertThat(AssignmentPolicy.conflicts(at(11, 0), at(11, 30), at(10, 0), at(11, 0))).isTrue();
    }

    @Test
    void startingExactlyAtBufferEndIsFree() {
        assertThat(AssignmentPolicy.conflicts(at(11, 30), at(12, 0), at(10, 0), at(11, 0))).isFalse();
    }

    @Test
    void endingInsideBufferBeforeNextJobConflicts() {
        // new 9:00-10:00, next job at 10:00 -> no travel time
        assertThat(AssignmentPolicy.conflicts(at(9, 0), at(10, 0), at(10, 0), at(11, 0))).isTrue();
    }

    @Test
    void endingExactlyBufferBeforeNextJobIsFree() {
        assertThat(AssignmentPolicy.conflicts(at(8, 30), at(9, 30), at(10, 0), at(11, 0))).isFalse();
    }

    @Test
    void cancelledBookingsDoNotBlock() {
        Technician t = tech(1, south, "BATTERY_FIT");
        Booking b = booking(t, battery, 10, 0);
        b.cancel();
        assertThat(AssignmentPolicy.isFree(List.of(b), at(10, 0), at(11, 0))).isTrue();
    }

    // ---- shift edges (rule 4) ----------------------------------------------------------------------

    @Test
    void shiftEdges() {
        Technician t = tech(1, south, "BATTERY_FIT");
        assertThat(AssignmentPolicy.withinShift(t, at(8, 0), at(9, 0))).isTrue();      // starts exactly at shift start
        assertThat(AssignmentPolicy.withinShift(t, at(7, 30), at(8, 30))).isFalse();   // starts before shift
        assertThat(AssignmentPolicy.withinShift(t, at(19, 0), at(20, 0))).isTrue();    // ends exactly at shift end
        assertThat(AssignmentPolicy.withinShift(t, at(19, 30), at(20, 30))).isFalse(); // runs past shift end
        assertThat(AssignmentPolicy.withinShift(t, at(20, 0), at(20, 30))).isFalse();
    }

    @Test
    void jobCrossingMidnightIsOutsideShift() {
        Technician t = new Technician(1L, "Night", south, Set.of("BATTERY_FIT"), LocalTime.of(0, 0), LocalTime.of(23, 59));
        assertThat(AssignmentPolicy.withinShift(t, at(23, 30), DAY.plusDays(1).atTime(0, 30))).isFalse();
    }

    // ---- skill and zone (rule 2) -------------------------------------------------------------------

    @Test
    void skillAndZoneChecks() {
        Technician t = tech(1, south, "JUMPSTART");
        assertThat(AssignmentPolicy.isQualified(t, jump, south)).isTrue();
        assertThat(AssignmentPolicy.isQualified(t, battery, south)).isFalse(); // missing skill
        assertThat(AssignmentPolicy.isQualified(t, jump, north)).isFalse();    // wrong zone
    }

    // ---- slots and windows (rules 1, 4, 7) ---------------------------------------------------------

    @Test
    void slotBoundary() {
        assertThat(AssignmentPolicy.onSlotBoundary(at(10, 0))).isTrue();
        assertThat(AssignmentPolicy.onSlotBoundary(at(10, 30))).isTrue();
        assertThat(AssignmentPolicy.onSlotBoundary(at(10, 15))).isFalse();
        assertThat(AssignmentPolicy.onSlotBoundary(at(10, 0).plusSeconds(1))).isFalse();
    }

    @Test
    void leadWindowEdges() {
        LocalDateTime now = at(10, 0);
        assertThat(AssignmentPolicy.withinLeadWindow(now.plusHours(2).minusMinutes(1), now)).isFalse();
        assertThat(AssignmentPolicy.withinLeadWindow(now.plusHours(2), now)).isTrue();
        assertThat(AssignmentPolicy.withinLeadWindow(now.plusDays(7), now)).isTrue();
        assertThat(AssignmentPolicy.withinLeadWindow(now.plusDays(7).plusMinutes(1), now)).isFalse();
    }

    @Test
    void cancellationCutoff() {
        LocalDateTime start = at(12, 0);
        assertThat(AssignmentPolicy.canCancel(start, at(10, 59))).isTrue();
        assertThat(AssignmentPolicy.canCancel(start, at(11, 0))).isTrue();
        assertThat(AssignmentPolicy.canCancel(start, at(11, 1))).isFalse();
    }

    // ---- assignment (rule 6) -----------------------------------------------------------------------

    @Test
    void picksTechnicianWithFewestBookingsThatDay() {
        Technician t1 = tech(1, south, "BATTERY_FIT");
        Technician t2 = tech(2, south, "BATTERY_FIT");
        List<Booking> existing = List.of(booking(t1, battery, 8, 0), booking(t1, battery, 14, 0), booking(t2, battery, 8, 0));

        assertThat(AssignmentPolicy.pick(List.of(t1, t2), existing, at(10, 0), at(11, 0))).contains(t2);
    }

    @Test
    void tieBreaksOnLowestId() {
        Technician t1 = tech(1, south, "BATTERY_FIT");
        Technician t2 = tech(2, south, "BATTERY_FIT");
        assertThat(AssignmentPolicy.pick(List.of(t2, t1), List.of(), at(10, 0), at(11, 0))).contains(t1);
    }

    @Test
    void skipsBusyTechnicianEvenIfTheyHaveFewerBookings() {
        Technician t1 = tech(1, south, "BATTERY_FIT");
        Technician t2 = tech(2, south, "BATTERY_FIT");
        // t1 has 1 booking (blocking 10:00), t2 has 2 (not blocking)
        List<Booking> existing = List.of(booking(t1, battery, 10, 0), booking(t2, battery, 8, 0), booking(t2, battery, 16, 0));

        assertThat(AssignmentPolicy.pick(List.of(t1, t2), existing, at(10, 0), at(11, 0))).contains(t2);
    }

    @Test
    void bookingsOnOtherDaysDoNotCountForLoad() {
        Technician t1 = tech(1, south, "BATTERY_FIT");
        Technician t2 = tech(2, south, "BATTERY_FIT");
        LocalDateTime yesterday = at(9, 0).minusDays(1);
        List<Booking> existing = List.of(
                new Booking("9999999999", battery, "560001", yesterday, yesterday.plusHours(1), t1, null, yesterday),
                new Booking("9999999999", battery, "560001", yesterday.minusHours(2), yesterday.minusHours(1), t1, null, yesterday),
                booking(t2, battery, 8, 0));

        assertThat(AssignmentPolicy.pick(List.of(t1, t2), existing, at(10, 0), at(11, 0))).contains(t1);
    }

    @Test
    void emptyWhenEveryoneIsBusyOrOutOfShift() {
        Technician t1 = tech(1, south, "BATTERY_FIT");
        assertThat(AssignmentPolicy.pick(List.of(t1), List.of(booking(t1, battery, 10, 0)), at(10, 30), at(11, 30))).isEmpty();
        assertThat(AssignmentPolicy.pick(List.of(t1), List.of(), at(19, 30), at(20, 30))).isEmpty();
    }
}
