package com.claims.staff;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByKeycloakSub(String keycloakSub);

    /** Adjuster candidates of one level, in deterministic (id) order — the tie-break base. */
    List<AppUser> findByLevelOrderById(String level);
}
