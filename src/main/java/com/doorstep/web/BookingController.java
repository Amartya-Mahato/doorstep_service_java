package com.doorstep.web;

import com.doorstep.service.BookingService;
import com.doorstep.web.dto.BookingResponse;
import com.doorstep.web.dto.CreateBookingRequest;
import com.doorstep.web.dto.PageResponse;
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

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    /** 201 for a new booking, 200 when the Idempotency-Key was seen before (original booking returned). */
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingService.CreateResult result = service.create(idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(BookingResponse.from(result.booking()));
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable UUID id) {
        return BookingResponse.from(service.get(id));
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id) {
        return BookingResponse.from(service.cancel(id));
    }

    @GetMapping
    public PageResponse<BookingResponse> byPhone(@RequestParam String phone,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.byPhone(phone, page, size), BookingResponse::from);
    }
}
