package com.doorstep.repo;

import com.doorstep.domain.Technician;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {

    List<Technician> findByZoneIdOrderById(Long zoneId);

    /**
     * SELECT ... FOR UPDATE on the technician rows, always in ascending id order so that two concurrent
     * bookings competing for the same technicians cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Technician t where t.id in :ids order by t.id")
    List<Technician> lockAllById(@Param("ids") Collection<Long> ids);
}
