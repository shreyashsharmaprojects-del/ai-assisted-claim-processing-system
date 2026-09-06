package com.claims.routing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Routing parameters per product code. Slice 1 uses {@code route_level} (the complexity
 * parameter) to classify FNOL claims. The monetary thresholds for the authority gate are
 * added by the migration of the slice that first evaluates them (decision slice).
 */
@Entity
@Table(name = "authority_config")
public class AuthorityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false, unique = true)
    private String productCode;

    @Column(name = "route_level", nullable = false)
    private String routeLevel;

    protected AuthorityConfig() {
        // for JPA
    }

    public AuthorityConfig(String productCode, String routeLevel) {
        this.productCode = productCode;
        this.routeLevel = routeLevel;
    }

    public Long getId() {
        return id;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getRouteLevel() {
        return routeLevel;
    }
}
