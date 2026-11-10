package com.doorstep.service;

import com.doorstep.domain.AssignmentPolicy;
import com.doorstep.domain.Booking;
import com.doorstep.domain.BookingStatus;
import com.doorstep.repo.BookingRepository;
import com.doorstep.repo.TechnicianRepository;
import com.doorstep.web.ApiException;
import com.doorstep.web.dto.CreateBookingRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    public record CreateResult(Booking booking, boolean created) {
    }

    private final BookingCreator creator;
    private final BookingRepository bookings;
    private final TechnicianRepository technicians;
    private final Clock clock;

    public BookingService(BookingCreator creator, BookingRepository bookings,
                          TechnicianRepository technicians, Clock clock) {
        this.creator = creator;
        this.bookings = bookings;
        this.technicians = technicians;
        this.clock = clock;
    }

    /**
     * Idempotent create. Deliberately NOT @Transactional: a retry (or a racing duplicate) that loses on the
     * unique idempotency_key constraint is caught here, after its transaction rolled back, and answered with
     * the booking that won.
     */
    public CreateResult create(String idempotencyKey, CreateBookingRequest req) {
        String key = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey.trim();
        if (key != null && key.length() > 255) {
            throw ApiException.badRequest("Idempotency-Key must be at most 255 characters");
        }
        if (key != null) {
            Optional<Booking> existing = bookings.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return new CreateResult(existing.get(), false);
            }
        }
        try {
            return new CreateResult(creator.create(key, req), true);
        } catch (DataIntegrityViolationException e) {
            if (key != null) {
                Optional<Booking> winner = bookings.findByIdempotencyKey(key);
                if (winner.isPresent()) {
                    return new CreateResult(winner.get(), false);
                }
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Booking get(UUID id) {
        return bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking " + id + " not found"));
    }

    @Transactional
    public Booking cancel(UUID id) {
        Booking booking = get(id);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("INVALID_STATE", "Booking is already " + booking.getStatus());
        }
        if (!AssignmentPolicy.canCancel(booking.getStartTime(), LocalDateTime.now(clock))) {
            throw ApiException.conflict("CANCEL_TOO_LATE", "Bookings can only be cancelled up to 1 hour before start");
        }
        booking.cancel();
        return bookings.save(booking);
    }

    @Transactional(readOnly = true)
    public List<Booking> schedule(Long technicianId, LocalDate date) {
        if (!technicians.existsById(technicianId)) {
            throw ApiException.notFound("Technician " + technicianId + " not found");
        }
        LocalDateTime from = date.atStartOfDay();
        return bookings.findSchedule(technicianId, BookingStatus.CANCELLED, from, from.plusDays(1));
    }

    @Transactional(readOnly = true)
    public Page<Booking> byPhone(String phone, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be >= 0 and size between 1 and 100");
        }
        return bookings.findByCustomerPhoneOrderByStartTimeDesc(phone, PageRequest.of(page, size));
    }
}
