package com.example.flaskopenaiapi.model;

import jakarta.persistence.*;

@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String team;
    private String role;
    private Double priceCr;
    private String nationality;
    
    private Integer matches = 0;
    private Integer runs = 0;
    private Double strikeRate = 0.0;
    private Integer wickets = 0;
    private Double economyRate = 0.0;
    
    private String availabilityStatus = "Available";
    
    @Column(length = 1000)
    private String injuryNotes;

    public Player() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Double getPriceCr() { return priceCr; }
    public void setPriceCr(Double priceCr) { this.priceCr = priceCr; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public Integer getMatches() { return matches; }
    public void setMatches(Integer matches) { this.matches = matches; }

    public Integer getRuns() { return runs; }
    public void setRuns(Integer runs) { this.runs = runs; }

    public Double getStrikeRate() { return strikeRate; }
    public void setStrikeRate(Double strikeRate) { this.strikeRate = strikeRate; }

    public Integer getWickets() { return wickets; }
    public void setWickets(Integer wickets) { this.wickets = wickets; }

    public Double getEconomyRate() { return economyRate; }
    public void setEconomyRate(Double economyRate) { this.economyRate = economyRate; }

    public String getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(String availabilityStatus) { this.availabilityStatus = availabilityStatus; }

    public String getInjuryNotes() { return injuryNotes; }
    public void setInjuryNotes(String injuryNotes) { this.injuryNotes = injuryNotes; }
}
