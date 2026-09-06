package com.claims.claim;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByClaimIdOrderById(Long claimId);
}
