package com.doorstep.web.dto;

import com.doorstep.domain.Booking;
import com.doorstep.domain.BookingStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        String customerPhone,
        ServiceRef service,
        String pincode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        TechnicianRef technician,
        BookingStatus status,
        LocalDateTime createdAt) {

    public record ServiceRef(Long id, String name, int durationMinutes) {
    }

    public record TechnicianRef(Long id, String name) {
    }

    public static BookingResponse from(Booking b) {
        return new BookingResponse(
                b.getId(),
                b.getCustomerPhone(),
                new ServiceRef(b.getService().getId(), b.getService().getName(), b.getService().getDurationMinutes()),
                b.getPincode(),
                b.getStartTime(),
                b.getEndTime(),
                new TechnicianRef(b.getTechnician().getId(), b.getTechnician().getName()),
                b.getStatus(),
                b.getCreatedAt());
    }
}
