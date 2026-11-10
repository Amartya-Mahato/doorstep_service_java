package com.doorstep.service;

import com.doorstep.domain.AssignmentPolicy;
import com.doorstep.domain.Booking;
import com.doorstep.domain.BookingStatus;
import com.doorstep.domain.ServiceType;
import com.doorstep.domain.Technician;
import com.doorstep.domain.Zone;
import com.doorstep.repo.BookingRepository;
import com.doorstep.repo.TechnicianRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    private final CatalogService catalog;
    private final TechnicianRepository technicians;
    private final BookingRepository bookings;
    private final Clock clock;

    public AvailabilityService(CatalogService catalog, TechnicianRepository technicians,
                               BookingRepository bookings, Clock clock) {
        this.catalog = catalog;
        this.technicians = technicians;
        this.bookings = bookings;
        this.clock = clock;
    }

    /** Start times on {@code date} for which at least one qualified technician is free (read-only, no locks). */
    @Transactional(readOnly = true)
    public List<LocalDateTime> bookableSlots(Long serviceId, String pincode, LocalDate date) {
        ServiceType service = catalog.service(serviceId);
        Zone zone = catalog.zoneForPincode(pincode);

        List<Technician> qualified = technicians.findByZoneIdOrderById(zone.getId()).stream()
                .filter(t -> AssignmentPolicy.isQualified(t, service, zone))
                .toList();
        if (qualified.isEmpty()) {
            return List.of();
        }

        LocalDateTime dayStart = date.atStartOfDay();
        List<Booking> active = bookings.findActive(
                qualified.stream().map(Technician::getId).toList(),
                BookingStatus.CANCELLED, dayStart, dayStart.plusDays(1));

        LocalDateTime now = LocalDateTime.now(clock);
        List<LocalDateTime> result = new ArrayList<>();
        for (LocalDateTime start = dayStart; start.toLocalDate().equals(date);
             start = start.plusMinutes(AssignmentPolicy.SLOT_MINUTES)) {
            if (!AssignmentPolicy.withinLeadWindow(start, now)) {
                continue;
            }
            LocalDateTime end = start.plusMinutes(service.getDurationMinutes());
            if (AssignmentPolicy.pick(qualified, active, start, end).isPresent()) {
                result.add(start);
            }
        }
        return result;
    }
}
