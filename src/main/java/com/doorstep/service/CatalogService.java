package com.doorstep.service;

import com.doorstep.domain.ServiceType;
import com.doorstep.domain.Zone;
import com.doorstep.repo.ServiceTypeRepository;
import com.doorstep.repo.ZoneRepository;
import com.doorstep.web.ApiException;
import org.springframework.stereotype.Service;

/** Lookups of seed/reference data with the right HTTP errors. */
@Service
public class CatalogService {

    private final ServiceTypeRepository services;
    private final ZoneRepository zones;

    public CatalogService(ServiceTypeRepository services, ZoneRepository zones) {
        this.services = services;
        this.zones = zones;
    }

    public ServiceType service(Long id) {
        return services.findById(id)
                .orElseThrow(() -> ApiException.notFound("Service " + id + " not found"));
    }

    public Zone zoneForPincode(String pincode) {
        return zones.findByPincode(pincode)
                .orElseThrow(() -> ApiException.unprocessable("PINCODE_NOT_SERVICEABLE",
                        "We do not serve pincode " + pincode));
    }
}
