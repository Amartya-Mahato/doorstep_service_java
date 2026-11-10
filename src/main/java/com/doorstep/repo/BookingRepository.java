package com.doorstep.repo;

import com.doorstep.domain.Booking;
import com.doorstep.domain.BookingStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /** Non-cancelled bookings of the given technicians that could interact (buffer included) with [from, to). */
    @Query("select b from Booking b where b.technician.id in :ids and b.status <> :cancelled "
            + "and b.startTime < :to and b.endTime > :from")
    List<Booking> findActive(@Param("ids") Collection<Long> ids,
                             @Param("cancelled") BookingStatus cancelled,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);

    @Query("select b from Booking b where b.technician.id = :techId and b.status <> :cancelled "
            + "and b.startTime >= :from and b.startTime < :to order by b.startTime")
    List<Booking> findSchedule(@Param("techId") Long techId,
                               @Param("cancelled") BookingStatus cancelled,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    Page<Booking> findByCustomerPhoneOrderByStartTimeDesc(String customerPhone, Pageable pageable);
}
