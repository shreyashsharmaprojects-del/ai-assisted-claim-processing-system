package com.claims.routing;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.InvalidRequestException;

/**
 * Slice 7: the authority-config surface. Reads list every row; updates replace a row's
 * three editable parameters (route level + L1/L2 limits) and return the saved row. There
 * is deliberately NO caching on the read side of this service or its consumers: the
 * classifier (FNOL) and the authority gate (decision) re-read {@code authority_config}
 * every call, so an edit feeds the next classification and the next decision immediately.
 */
@Service
public class AuthorityConfigService {

    /** The l1/l2_limit_amount columns are NUMERIC(14,2), like the claim money columns. */
    private static final BigDecimal MAX_LIMIT = new BigDecimal("999999999999.99");

    private final AuthorityConfigRepository configs;

    public AuthorityConfigService(AuthorityConfigRepository configs) {
        this.configs = configs;
    }

    public List<AuthorityConfigView> list() {
        return configs.findAll().stream()
                .sorted(Comparator.comparing(AuthorityConfig::getProductCode))
                .map(AuthorityConfigView::from)
                .toList();
    }

    @Transactional
    public AuthorityConfigView update(String productCode, String routeLevel,
            BigDecimal l1LimitAmount, BigDecimal l2LimitAmount) {
        String error = validate(routeLevel, l1LimitAmount, l2LimitAmount);
        if (error != null) {
            throw new InvalidRequestException(error);
        }
        AuthorityConfig config = configs.findByProductCode(productCode)
                .orElseThrow(() -> new ConfigNotFoundException(
                        "No authority configuration exists for product " + productCode + "."));
        // The row is a managed entity (fetched in this transaction); dirty checking persists
        // the setters on commit.
        config.setRouteLevel(routeLevel);
        config.setL1LimitAmount(l1LimitAmount);
        config.setL2LimitAmount(l2LimitAmount);
        return AuthorityConfigView.from(config);
    }

    /** @return a user-actionable message, or {@code null} when the three params are legal. */
    static String validate(String routeLevel, BigDecimal l1LimitAmount, BigDecimal l2LimitAmount) {
        if (!"L1".equals(routeLevel) && !"L2".equals(routeLevel)) {
            return "Route level must be L1 or L2.";
        }
        String l1Error = validateLimit("L1 limit", l1LimitAmount);
        if (l1Error != null) {
            return l1Error;
        }
        String l2Error = validateLimit("L2 limit", l2LimitAmount);
        if (l2Error != null) {
            return l2Error;
        }
        if (l1LimitAmount.compareTo(l2LimitAmount) > 0) {
            return "The L1 limit cannot exceed the L2 limit.";
        }
        return null;
    }

    private static String validateLimit(String label, BigDecimal amount) {
        if (amount == null) {
            return label + " is required.";
        }
        if (amount.signum() <= 0) {
            return label + " must be greater than zero.";
        }
        if (amount.scale() > 2) {
            return label + " may have at most 2 decimal places.";
        }
        if (amount.compareTo(MAX_LIMIT) > 0) {
            return label + " is too large (maximum 999999999999.99).";
        }
        return null;
    }
}
