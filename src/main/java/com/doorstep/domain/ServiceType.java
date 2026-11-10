package com.doorstep.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "service_types")
public class ServiceType {
    @Id
    private Long id;
    private String name;
    @Column(name = "duration_minutes")
    private int durationMinutes;
    @Column(name = "required_skill")
    private String requiredSkill;

    protected ServiceType() {
    }

    public ServiceType(Long id, String name, int durationMinutes, String requiredSkill) {
        this.id = id;
        this.name = name;
        this.durationMinutes = durationMinutes;
        this.requiredSkill = requiredSkill;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getDurationMinutes() { return durationMinutes; }
    public String getRequiredSkill() { return requiredSkill; }
}
