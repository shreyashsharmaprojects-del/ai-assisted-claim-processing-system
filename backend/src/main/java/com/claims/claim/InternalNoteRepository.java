package com.claims.claim;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InternalNoteRepository extends JpaRepository<InternalNote, Long> {

    List<InternalNote> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);
}
