package com.doorstep.web;

import com.doorstep.service.AvailabilityService;
import com.doorstep.service.BookingService;
import com.doorstep.web.dto.BookingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Availability and schedules")
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

    @Operation(summary = "List bookable start times for a service, pincode and date")
    @ApiResponse(responseCode = "200", description = "Slots (empty outside the 2-hour to 7-day window)")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (service)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "PINCODE_NOT_SERVICEABLE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/availability")
    public AvailabilityResponse availability(
            @RequestParam Long serviceId,
            @RequestParam String pincode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new AvailabilityResponse(serviceId, pincode, date, availability.bookableSlots(serviceId, pincode, date));
    }

    @Operation(summary = "A technician's non-cancelled bookings for a date, by start time")
    @ApiResponse(responseCode = "200", description = "Schedule")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (technician)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/technicians/{id}/schedule")
    public ScheduleResponse schedule(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new ScheduleResponse(id, date,
                bookings.schedule(id, date).stream().map(BookingResponse::from).toList());
    }
}
