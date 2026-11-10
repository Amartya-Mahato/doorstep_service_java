package com.doorstep.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "zones")
public class Zone {
    @Id
    private Long id;
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "zone_pincodes", joinColumns = @JoinColumn(name = "zone_id"))
    @Column(name = "pincode")
    private Set<String> pincodes = new HashSet<>();

    protected Zone() {
    }

    public Zone(Long id, String name, Set<String> pincodes) {
        this.id = id;
        this.name = name;
        this.pincodes = pincodes;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Set<String> getPincodes() { return pincodes; }
}
