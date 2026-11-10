package com.doorstep.service;

import com.doorstep.domain.AssignmentPolicy;
import com.doorstep.domain.Booking;
import com.doorstep.domain.BookingStatus;
import com.doorstep.domain.ServiceType;
import com.doorstep.domain.Technician;
import com.doorstep.domain.Zone;
import com.doorstep.repo.BookingRepository;
import com.doorstep.repo.TechnicianRepository;
import com.doorstep.web.ApiException;
import com.doorstep.web.dto.CreateBookingRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that writes new bookings. Kept in its own bean so the transaction boundary is the proxy
 * boundary and {@link BookingService} can handle commit-time failures (idempotency races) outside it.
 */
@Service
public class BookingCreator {

    private final CatalogService catalog;
    private final TechnicianRepository technicians;
    private final BookingRepository bookings;
    private final Clock clock;

    public BookingCreator(CatalogService catalog, TechnicianRepository technicians,
                          BookingRepository bookings, Clock clock) {
        this.catalog = catalog;
        this.technicians = technicians;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Transactional
    public Booking create(String idempotencyKey, CreateBookingRequest req) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start = req.startTime();

        ServiceType service = catalog.service(req.serviceId());
        Zone zone = catalog.zoneForPincode(req.pincode());

        if (!AssignmentPolicy.onSlotBoundary(start)) {
            throw ApiException.unprocessable("INVALID_SLOT", "startTime must be on a 30-minute boundary (e.g. 10:00 or 10:30)");
        }
        if (!AssignmentPolicy.withinLeadWindow(start, now)) {
            throw ApiException.unprocessable("OUTSIDE_BOOKING_WINDOW",
                    "startTime must be between 2 hours and 7 days from now");
        }
        LocalDateTime end = start.plusMinutes(service.getDurationMinutes());

        // Rules 2 + 4: technicians that could do this job at all (zone, skill, shift).
        List<Technician> candidates = technicians.findByZoneIdOrderById(zone.getId()).stream()
                .filter(t -> AssignmentPolicy.isQualified(t, service, zone))
                .filter(t -> AssignmentPolicy.withinShift(t, start, end))
                .toList();
        if (candidates.isEmpty()) {
            throw noAvailability();
        }

        // Rule 5: serialise competing bookings by locking every candidate row (ascending id => no deadlocks).
        // Everything below sees committed state because the locks are held until this transaction ends.
        List<Technician> locked = technicians.lockAllById(candidates.stream().map(Technician::getId).toList());

        LocalDateTime dayStart = start.toLocalDate().atStartOfDay();
        List<Booking> active = bookings.findActive(locked.stream().map(Technician::getId).toList(),
                BookingStatus.CANCELLED, dayStart, dayStart.plusDays(1));

        // Rules 3 + 6: free (with buffer) technician with the fewest bookings that day; ties -> lowest id.
        Technician chosen = AssignmentPolicy.pick(locked, active, start, end).orElseThrow(this::noAvailability);

        // saveAndFlush so a duplicate Idempotency-Key surfaces here, not at commit time.
        return bookings.saveAndFlush(new Booking(req.customerPhone(), service, req.pincode(), start, end,
                chosen, idempotencyKey, now));
    }

    private ApiException noAvailability() {
        return ApiException.conflict("NO_AVAILABILITY", "No technician is available for the requested slot");
    }
}
