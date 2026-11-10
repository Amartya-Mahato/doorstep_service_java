package com.doorstep.repo;

import com.doorstep.domain.Zone;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ZoneRepository extends JpaRepository<Zone, Long> {

    @Query("select z from Zone z join z.pincodes p where p = :pincode")
    Optional<Zone> findByPincode(@Param("pincode") String pincode);
}
