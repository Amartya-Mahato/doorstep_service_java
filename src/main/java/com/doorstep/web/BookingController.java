package com.doorstep.web;

import com.doorstep.service.BookingService;
import com.doorstep.web.dto.BookingResponse;
import com.doorstep.web.dto.CreateBookingRequest;
import com.doorstep.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bookings")
@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    /** 201 for a new booking, 200 when the Idempotency-Key was seen before (original booking returned). */
    @Operation(summary = "Create a booking and auto-assign a technician")
    @ApiResponse(responseCode = "201", description = "Booking created")
    @ApiResponse(responseCode = "200", description = "Idempotency-Key seen before; original booking returned")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (service)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "NO_AVAILABILITY", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "INVALID_SLOT, OUTSIDE_BOOKING_WINDOW, PINCODE_NOT_SERVICEABLE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingService.CreateResult result = service.create(idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(BookingResponse.from(result.booking()));
    }

    @Operation(summary = "Get a booking by id")
    @ApiResponse(responseCode = "200", description = "Booking found")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST (id is not a UUID)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable UUID id) {
        return BookingResponse.from(service.get(id));
    }

    @Operation(summary = "Cancel a booking (allowed until 1 hour before start)")
    @ApiResponse(responseCode = "200", description = "Booking cancelled")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CANCEL_TOO_LATE, INVALID_STATE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id) {
        return BookingResponse.from(service.cancel(id));
    }

    @Operation(summary = "List bookings for a customer phone, newest first")
    @ApiResponse(responseCode = "200", description = "Page of bookings")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST (page < 0 or size not 1-100)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    public PageResponse<BookingResponse> byPhone(@RequestParam String phone,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.byPhone(phone, page, size), BookingResponse::from);
    }
}
