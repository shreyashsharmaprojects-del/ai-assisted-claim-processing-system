package com.claims.assignment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdjusterSkillRepository extends JpaRepository<AdjusterSkill, AdjusterSkill.Key> {

    List<AdjusterSkill> findByKeyProductCode(String productCode);
}
