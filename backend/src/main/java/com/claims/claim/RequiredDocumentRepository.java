package com.claims.claim;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RequiredDocumentRepository extends JpaRepository<RequiredDocument, Long> {

    List<RequiredDocument> findByProductCodeOrderBySortOrderAscIdAsc(String productCode);

    Optional<RequiredDocument> findByProductCodeAndDocKey(String productCode, String docKey);
}
