package com.doorstep.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_phone")
    private String customerPhone;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "service_id")
    private ServiceType service;

    private String pincode;

    @Column(name = "start_time")
    private LocalDateTime startTime;
    @Column(name = "end_time")
    private LocalDateTime endTime;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "technician_id")
    private Technician technician;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected Booking() {
    }

    public Booking(String customerPhone, ServiceType service, String pincode, LocalDateTime startTime,
                   LocalDateTime endTime, Technician technician, String idempotencyKey, LocalDateTime createdAt) {
        this.customerPhone = customerPhone;
        this.service = service;
        this.pincode = pincode;
        this.startTime = startTime;
        this.endTime = endTime;
        this.technician = technician;
        this.status = BookingStatus.CONFIRMED;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    public UUID getId() { return id; }
    public String getCustomerPhone() { return customerPhone; }
    public ServiceType getService() { return service; }
    public String getPincode() { return pincode; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public Technician getTechnician() { return technician; }
    public BookingStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
