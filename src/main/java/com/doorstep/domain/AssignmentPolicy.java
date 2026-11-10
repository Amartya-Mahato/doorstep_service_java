package com.doorstep.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pure business rules (no Spring, no DB) so they can be unit tested exhaustively.
 * Rule numbers refer to the problem statement.
 */
public final class AssignmentPolicy {

    public static final int BUFFER_MINUTES = 30;
    public static final int SLOT_MINUTES = 30;
    public static final Duration MIN_LEAD = Duration.ofHours(2);
    public static final Duration MAX_LEAD = Duration.ofDays(7);
    public static final Duration CANCEL_CUTOFF = Duration.ofHours(1);

    private AssignmentPolicy() {
    }

    /** Rule 1: slots start on 30-minute boundaries. */
    public static boolean onSlotBoundary(LocalDateTime t) {
        return t.getSecond() == 0 && t.getNano() == 0 && t.getMinute() % SLOT_MINUTES == 0;
    }

    /** Rule 4 (second half): between 2 hours and 7 days from now, both inclusive. */
    public static boolean withinLeadWindow(LocalDateTime start, LocalDateTime now) {
        return !start.isBefore(now.plus(MIN_LEAD)) && !start.isAfter(now.plus(MAX_LEAD));
    }

    /** Rule 2: technician works the pincode's zone and has the service's skill. */
    public static boolean isQualified(Technician t, ServiceType service, Zone pincodeZone) {
        return t.getZone().getId().equals(pincodeZone.getId())
                && t.getSkills().contains(service.getRequiredSkill());
    }

    /** Rule 4 (first half): the whole job sits inside the shift of a single day. */
    public static boolean withinShift(Technician t, LocalDateTime start, LocalDateTime end) {
        return end.toLocalDate().equals(start.toLocalDate())
                && !start.toLocalTime().isBefore(t.getShiftStart())
                && !end.toLocalTime().isAfter(t.getShiftEnd());
    }

    /**
     * Rules 3 and 5: two jobs conflict when the gap between them is less than the travel buffer.
     * Back-to-back with exactly 30 min between them is fine.
     */
    public static boolean conflicts(LocalDateTime start, LocalDateTime end,
                                    LocalDateTime otherStart, LocalDateTime otherEnd) {
        return otherStart.isBefore(end.plusMinutes(BUFFER_MINUTES))
                && otherEnd.isAfter(start.minusMinutes(BUFFER_MINUTES));
    }

    /** True if none of the technician's non-cancelled bookings conflicts with [start, end). */
    public static boolean isFree(Collection<Booking> techBookings, LocalDateTime start, LocalDateTime end) {
        return techBookings.stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED)
                .noneMatch(b -> conflicts(start, end, b.getStartTime(), b.getEndTime()));
    }

    /**
     * Rule 6: among the candidates that are in shift and free, pick the one with the fewest bookings on that day.
     * Ties are broken by the lowest technician id (deterministic, easy to reason about).
     *
     * @param activeBookings bookings of the candidates; cancelled ones and other technicians' are ignored
     */
    public static Optional<Technician> pick(Collection<Technician> candidates, Collection<Booking> activeBookings,
                                            LocalDateTime start, LocalDateTime end) {
        Map<Long, List<Booking>> byTech = activeBookings.stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED)
                .collect(Collectors.groupingBy(b -> b.getTechnician().getId()));

        return candidates.stream()
                .filter(t -> withinShift(t, start, end))
                .filter(t -> isFree(byTech.getOrDefault(t.getId(), List.of()), start, end))
                .min(Comparator
                        .comparingLong((Technician t) -> byTech.getOrDefault(t.getId(), List.of()).stream()
                                .filter(b -> b.getStartTime().toLocalDate().equals(start.toLocalDate()))
                                .count())
                        .thenComparing(Technician::getId));
    }

    /** Rule 7: cancellation allowed up to 1 hour before start (inclusive). */
    public static boolean canCancel(LocalDateTime start, LocalDateTime now) {
        return !now.isAfter(start.minus(CANCEL_CUTOFF));
    }
}
