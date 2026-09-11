package com.claims.clause;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyClauseRepository extends JpaRepository<PolicyClause, Long> {

    /**
     * Catalogue: every clause of a product, or — when {@code coverCode} is
     * non-null — that cover's rows plus the product-level rows. Ordered for
     * display. Unknown products yield an empty list (never 404).
     */
    @Query("select p from PolicyClause p where p.productCode = :productCode"
            + " and (:coverCode is null or p.coverCode is null"
            + " or p.coverCode = :coverCode)"
            + " order by p.sortOrder asc, p.id asc")
    List<PolicyClause> findCatalogue(@Param("productCode") String productCode,
            @Param("coverCode") String coverCode);

    /**
     * Claim view: clauses of the product in force on the claim's loss date,
     * restricted to product-level rows plus the claim's own covers. Ordered
     * for display.
     */
    @Query("select p from PolicyClause p where p.productCode = :productCode"
            + " and p.effectiveFrom <= :lossDate"
            + " and (p.effectiveTo is null or p.effectiveTo > :lossDate)"
            + " and (p.coverCode is null or p.coverCode in :coverCodes)"
            + " order by p.sortOrder asc, p.id asc")
    List<PolicyClause> findForClaim(@Param("productCode") String productCode,
            @Param("lossDate") LocalDate lossDate,
            @Param("coverCodes") List<String> coverCodes);
}
