package com.doorstep.web;

import com.doorstep.service.AvailabilityService;
import com.doorstep.service.BookingService;
import com.doorstep.web.dto.BookingResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController {

    public record AvailabilityResponse(Long serviceId, String pincode, LocalDate date, List<LocalDateTime> slots) {
    }

    public record ScheduleResponse(Long technicianId, LocalDate date, List<BookingResponse> bookings) {
    }

    private final AvailabilityService availability;
    private final BookingService bookings;

    public QueryController(AvailabilityService availability, BookingService bookings) {
        this.availability = availability;
        this.bookings = bookings;
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(
            @RequestParam Long serviceId,
            @RequestParam String pincode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new AvailabilityResponse(serviceId, pincode, date, availability.bookableSlots(serviceId, pincode, date));
    }

    @GetMapping("/technicians/{id}/schedule")
    public ScheduleResponse schedule(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new ScheduleResponse(id, date,
                bookings.schedule(id, date).stream().map(BookingResponse::from).toList());
    }
}
