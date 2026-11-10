package com.doorstep.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "technicians")
public class Technician {
    @Id
    private Long id;
    private String name;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "technician_skills", joinColumns = @JoinColumn(name = "technician_id"))
    @Column(name = "skill")
    private Set<String> skills = new HashSet<>();

    @Column(name = "shift_start")
    private LocalTime shiftStart;
    @Column(name = "shift_end")
    private LocalTime shiftEnd;

    protected Technician() {
    }

    public Technician(Long id, String name, Zone zone, Set<String> skills, LocalTime shiftStart, LocalTime shiftEnd) {
        this.id = id;
        this.name = name;
        this.zone = zone;
        this.skills = skills;
        this.shiftStart = shiftStart;
        this.shiftEnd = shiftEnd;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Zone getZone() { return zone; }
    public Set<String> getSkills() { return skills; }
    public LocalTime getShiftStart() { return shiftStart; }
    public LocalTime getShiftEnd() { return shiftEnd; }
}
