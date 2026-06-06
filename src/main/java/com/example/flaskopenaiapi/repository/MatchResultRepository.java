package com.example.flaskopenaiapi.repository;

import com.example.flaskopenaiapi.model.MatchResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchResultRepository extends JpaRepository<MatchResult, Long> {
    boolean existsByDateAndTeamAAndTeamB(String date, String teamA, String teamB);
}
