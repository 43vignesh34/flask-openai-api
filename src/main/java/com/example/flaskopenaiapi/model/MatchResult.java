package com.example.flaskopenaiapi.model;

import jakarta.persistence.*;

@Entity
@Table(name = "match_results")
public class MatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String date;

    @Column(name = "team_a", nullable = false)
    private String teamA;

    @Column(name = "team_b", nullable = false)
    private String teamB;

    private String venue;
    private String winner;
    private String margin;

    @Column(name = "player_of_match")
    private String playerOfMatch;

    public MatchResult() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getTeamA() { return teamA; }
    public void setTeamA(String teamA) { this.teamA = teamA; }

    public String getTeamB() { return teamB; }
    public void setTeamB(String teamB) { this.teamB = teamB; }

    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }

    public String getMargin() { return margin; }
    public void setMargin(String margin) { this.margin = margin; }

    public String getPlayerOfMatch() { return playerOfMatch; }
    public void setPlayerOfMatch(String playerOfMatch) { this.playerOfMatch = playerOfMatch; }
}
