package com.claims.assignment;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * V2-1: adjuster↔product eligibility matrix. Assignment eligibility only — approval
 * authority stays on {@code app_user.level} (locked clarification 5). Consumed by
 * assignment in V2-3; V2-1 only seeds and exposes it for admin/tests.
 */
@Entity
@Table(name = "adjuster_skill")
public class AdjusterSkill {

    @EmbeddedId
    private Key key;

    protected AdjusterSkill() {
        // for JPA
    }

    public AdjusterSkill(Long adjusterId, String productCode) {
        this.key = new Key(adjusterId, productCode);
    }

    public Long getAdjusterId() {
        return key.adjusterId;
    }

    public String getProductCode() {
        return key.productCode;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "adjuster_id")
        private Long adjusterId;

        @Column(name = "product_code")
        private String productCode;

        protected Key() {
            // for JPA
        }

        public Key(Long adjusterId, String productCode) {
            this.adjusterId = adjusterId;
            this.productCode = productCode;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(adjusterId, that.adjusterId)
                    && Objects.equals(productCode, that.productCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(adjusterId, productCode);
        }
    }
}
