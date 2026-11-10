package com.doorstep.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

/** startTime is an IST local date-time, e.g. 2026-11-12T10:00:00 (no offset). */
public record CreateBookingRequest(
        @NotNull Long serviceId,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit pincode") String pincode,
        @NotNull LocalDateTime startTime,
        @NotBlank @Pattern(regexp = "\\+?\\d{10,15}", message = "must be 10-15 digits, optional leading +") String customerPhone) {
}
