package com.claims.policy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyCoverRepository extends JpaRepository<PolicyCover, Long> {

    List<PolicyCover> findByPolicyIdOrderBySortOrderAscIdAsc(Long policyId);

    Optional<PolicyCover> findByPolicyIdAndCoverCode(Long policyId, String coverCode);
}
