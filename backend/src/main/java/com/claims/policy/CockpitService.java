package com.claims.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import com.claims.catalog.ProductRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V2-1 claimant cockpit read path: "my policies" (rows whose holder email matches the
 * caller's account email — the V2 link key) plus full policy detail with covers,
 * rating parameters, and clauses.
 *
 * <p>Remaining limits (locked rule 2) are derived here, never stored: remaining policy
 * benefit = {@code sum_insured − Σ prior non-rejected net payables on the policy};
 * remaining per-cover sub-limit = {@code sub_limit − Σ prior non-rejected net payables
 * on that cover}. Until claim_cover exists (V2-2), consumed amounts come from closed
 * V1 claims' indemnity figures, attributed to the policy's first cover in sort order
 * (the degenerate single-cover case — exact per-cover attribution starts in V2-2).
 * Exhaustion of one cover never touches the others: each cover's remaining is
 * computed from its own sub-limit and its own consumed total only.
 *
 * <p>Claimant-safe by construction: holder email and internal fields never enter the
 * returned shapes (same discipline as the V1 visibility wall).
 */
@Service
public class CockpitService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final ProductRepository products;

    public CockpitService(JdbcTemplate jdbcTemplate, ProductRepository products) {
        this.jdbcTemplate = jdbcTemplate;
        this.products = products;
    }

    /** Own policies for the caller's account email, policy-number order. */
    public List<CockpitPolicyView> myPolicies(String holderEmail) {
        List<CockpitPolicyView> rows = jdbcTemplate.query(
                "SELECT p.policy_number, p.product_code, p.holder_name, p.holder_email, "
                        + "p.status, p.sum_insured, "
                        + "p.valid_from, p.valid_to, "
                        + "(SELECT count(*) FROM policy_cover pc WHERE pc.policy_id = p.id) AS cover_count "
                        + "FROM policy p WHERE lower(p.holder_email) = lower(?) "
                        + "ORDER BY p.policy_number",
                policyRow(), holderEmail.trim());
        Map<String, BigDecimal> consumedByPolicy = consumedByPolicy(holderEmail);
        Map<String, String> families = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        products.findAll().forEach(
                product -> {
                    families.put(product.getCode(), product.getFamily());
                    displayNames.put(product.getCode(), product.getDisplayName());
                });
        List<CockpitPolicyView> views = new ArrayList<>();
        for (CockpitPolicyView row : rows) {
            BigDecimal consumed = consumedByPolicy.getOrDefault(row.policyNumber(),
                    BigDecimal.ZERO);
            BigDecimal remaining = row.sumInsured() == null ? null
                    : row.sumInsured().subtract(consumed);
            views.add(new CockpitPolicyView(row.policyNumber(), row.productCode(),
                    families.getOrDefault(row.productCode(), "NON_HEALTH"),
                    displayNames.getOrDefault(row.productCode(), row.productCode()),
                    row.holderName(), row.holderEmail(), row.status(), row.sumInsured(),
                    remaining, row.validFrom(), row.validTo(), row.coverCount()));
        }
        return views;
    }

    /** Full detail for one own policy; null when not owned (caller maps to 404). */
    public CockpitPolicyDetailView policyDetail(String policyNumber, String holderEmail) {
        List<CockpitPolicyView> mine = myPolicies(holderEmail);
        CockpitPolicyView policy = mine.stream()
                .filter(row -> row.policyNumber().equals(policyNumber))
                .findFirst()
                .orElse(null);
        if (policy == null) {
            return null;
        }
        return assembleDetail(policyNumber, policy);
    }

    /**
     * Staff read path (adjusters/supervisor — the claim workspace policy modal):
     * same assembled shape, loaded by policy number with no holder-email
     * ownership check. The route already restricts to authenticated internal
     * roles; staff see holder identity on the claim itself, so nothing new
     * leaks. Unknown numbers still return null (caller maps to 404).
     */
    public CockpitPolicyDetailView policyDetailForStaff(String policyNumber) {
        CockpitPolicyView policy = policyRow(policyNumber);
        if (policy == null) {
            return null;
        }
        return assembleDetail(policyNumber, policy);
    }

    /** True for internal roles (adjusters + supervisor), false for claimants. */
    public static boolean isStaff(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (String role : List.of("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3", "SUPERVISOR")) {
            if (authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_" + role))) {
                return true;
            }
        }
        return false;
    }

    /** One policy row by number with derived remaining benefit (no email filter). */
    private CockpitPolicyView policyRow(String policyNumber) {
        List<CockpitPolicyView> rows = jdbcTemplate.query(
                "SELECT p.policy_number, p.product_code, p.holder_name, p.holder_email, "
                        + "p.status, p.sum_insured, "
                        + "p.valid_from, p.valid_to, "
                        + "(SELECT count(*) FROM policy_cover pc WHERE pc.policy_id = p.id) AS cover_count "
                        + "FROM policy p WHERE p.policy_number = ?",
                policyRow(), policyNumber);
        if (rows.isEmpty()) {
            return null;
        }
        CockpitPolicyView row = withProductMeta(rows.get(0));
        BigDecimal consumed = consumedByPolicyNumber(policyNumber);
        BigDecimal remaining = row.sumInsured() == null ? null
                : row.sumInsured().subtract(consumed);
        return new CockpitPolicyView(row.policyNumber(), row.productCode(),
                row.productFamily(), row.productDisplayName(),
                row.holderName(), row.holderEmail(), row.status(), row.sumInsured(),
                remaining, row.validFrom(), row.validTo(), row.coverCount());
    }

    /** Prior non-rejected consumption for one policy number (staff path). */
    private BigDecimal consumedByPolicyNumber(String policyNumber) {
        BigDecimal consumed = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(c.indemnity_amount), 0) FROM claim c "
                        + "JOIN policy p ON p.id = c.policy_id "
                        + "WHERE p.policy_number = ? "
                        + "AND c.decision IS DISTINCT FROM 'DENIED' "
                        + "AND c.indemnity_amount IS NOT NULL",
                BigDecimal.class, policyNumber);
        return consumed == null ? BigDecimal.ZERO : consumed;
    }

    /** Product family/display enrichment shared by both read paths. */
    private CockpitPolicyView withProductMeta(CockpitPolicyView row) {
        Map<String, String> families = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        products.findAll().forEach(
                product -> {
                    families.put(product.getCode(), product.getFamily());
                    displayNames.put(product.getCode(), product.getDisplayName());
                });
        return new CockpitPolicyView(row.policyNumber(), row.productCode(),
                families.getOrDefault(row.productCode(), "NON_HEALTH"),
                displayNames.getOrDefault(row.productCode(), row.productCode()),
                row.holderName(), row.holderEmail(), row.status(), row.sumInsured(),
                row.remainingBenefit(), row.validFrom(), row.validTo(), row.coverCount());
    }

    /** Covers + remaining + rating/clauses assembly shared by both read paths. */
    private CockpitPolicyDetailView assembleDetail(String policyNumber, CockpitPolicyView policy) {
        Long policyId = jdbcTemplate.queryForObject(
                "SELECT id FROM policy WHERE policy_number = ?", Long.class, policyNumber);
        List<CockpitCoverView> covers = jdbcTemplate.query(
                "SELECT cover_code, display_name, sub_limit, deductible_default "
                        + "FROM policy_cover WHERE policy_id = ? "
                        + "ORDER BY sort_order, id",
                coverRow(policyId), policyId);
        Map<String, BigDecimal> consumedByCover = consumedByCover(policyId);
        List<CockpitCoverView> withRemaining = new ArrayList<>();
        for (CockpitCoverView cover : covers) {
            BigDecimal consumed = consumedByCover.getOrDefault(cover.coverCode(),
                    BigDecimal.ZERO);
            withRemaining.add(new CockpitCoverView(cover.coverCode(), cover.displayName(),
                    cover.subLimit(), cover.deductibleDefault(),
                    cover.subLimit().subtract(consumed)));
        }
        Map<String, JsonNode> json = readPolicyJson(policyId);
        return new CockpitPolicyDetailView(policy, withRemaining,
                json.get("rating_params"), json.get("clauses"));
    }

    private Map<String, JsonNode> readPolicyJson(Long policyId) {
        String rowJson = jdbcTemplate.queryForObject(
                "SELECT row_to_json(t)::text FROM "
                        + "(SELECT rating_params, clauses FROM policy WHERE id = ?) t",
                String.class, policyId);
        return readJson(rowJson);
    }

    // --- derived consumption (V1-claim bridge until claim_cover lands in V2-2) -------

    /**
     * Prior non-rejected consumption per policy number: closed V1 claims carry a single
     * indemnity figure with no cover split, so the whole figure counts against the
     * policy total. DENIED claims (indemnity null) consume nothing.
     */
    private Map<String, BigDecimal> consumedByPolicy(String holderEmail) {
        Map<String, BigDecimal> consumed = new HashMap<>();
        jdbcTemplate.query(
                "SELECT p.policy_number AS policy_number, "
                        + "coalesce(sum(c.indemnity_amount), 0) AS consumed "
                        + "FROM policy p LEFT JOIN claim c ON c.policy_id = p.id "
                        + "AND c.decision IS DISTINCT FROM 'DENIED' "
                        + "AND c.indemnity_amount IS NOT NULL "
                        + "WHERE lower(p.holder_email) = lower(?) "
                        + "GROUP BY p.policy_number",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> consumed.put(
                        rs.getString("policy_number"), rs.getBigDecimal("consumed")),
                holderEmail.trim());
        return consumed;
    }

    /**
     * Prior non-rejected consumption per cover code: attributed to the policy's first
     * cover in sort order (V1 claims predate cover splits). Policies with several
     * covers therefore show consumption on the first cover only until V2-2 records
     * per-cover payables — a documented bridge, not a semantic: each cover's
     * remaining still derives from its own sub-limit minus its own attributed total.
     */
    private Map<String, BigDecimal> consumedByCover(Long policyId) {
        Map<String, BigDecimal> consumed = new HashMap<>();
        String firstCover = jdbcTemplate.query(
                "SELECT cover_code FROM policy_cover WHERE policy_id = ? "
                        + "ORDER BY sort_order, id LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, policyId);
        if (firstCover == null) {
            return consumed;
        }
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(c.indemnity_amount), 0) FROM claim c "
                        + "WHERE c.policy_id = ? AND c.decision IS DISTINCT FROM 'DENIED' "
                        + "AND c.indemnity_amount IS NOT NULL",
                BigDecimal.class, policyId);
        consumed.put(firstCover, total == null ? BigDecimal.ZERO : total);
        return consumed;
    }

    // --- row mapping -------------------------------------------------------------------

    private static RowMapper<CockpitPolicyView> policyRow() {
        return (rs, rowNum) -> new CockpitPolicyView(
                rs.getString("policy_number"),
                rs.getString("product_code"),
                null,
                null,
                rs.getString("holder_name"),
                rs.getString("holder_email"),
                rs.getString("status"),
                rs.getBigDecimal("sum_insured"),
                null,
                rs.getObject("valid_from", java.time.LocalDate.class),
                rs.getObject("valid_to", java.time.LocalDate.class),
                rs.getInt("cover_count"));
    }

    private RowMapper<CockpitCoverView> coverRow(Long policyId) {
        return (rs, rowNum) -> new CockpitCoverView(
                rs.getString("cover_code"),
                rs.getString("display_name"),
                rs.getBigDecimal("sub_limit"),
                rs.getBigDecimal("deductible_default"),
                rs.getBigDecimal("sub_limit"));
    }

    private static Map<String, JsonNode> readJson(String rowJson) {
        Map<String, JsonNode> out = new HashMap<>();
        if (rowJson == null) {
            out.put("rating_params", JSON.createObjectNode());
            out.put("clauses", JSON.createObjectNode());
            return out;
        }
        try {
            JsonNode row = JSON.readTree(rowJson);
            out.put("rating_params", row.path("rating_params"));
            out.put("clauses", row.path("clauses"));
        } catch (tools.jackson.core.JacksonException ex) {
            out.put("rating_params", JSON.createObjectNode());
            out.put("clauses", JSON.createObjectNode());
        }
        return out;
    }
}
