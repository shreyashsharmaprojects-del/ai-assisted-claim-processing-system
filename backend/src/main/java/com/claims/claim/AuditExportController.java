package com.claims.claim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;

/**
 * V23 (V3 S8): regulator-ready CSV exports, SUPERVISOR-only at the URL (see
 * {@code SecurityConfig}) — the claim audit story and the closure list stream
 * via {@link JdbcTemplate}, {@code text/csv}, {@code Content-Disposition:
 * attachment}. Unknown claims are a 404, never a 403 (claimant wall).
 */
@RestController
@RequestMapping("/api")
public class AuditExportController {

    private final JdbcTemplate jdbcTemplate;

    public AuditExportController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The claim's audit trail as CSV: at, actor, action, before, after, rationale. */
    @GetMapping("/audit/export")
    public ResponseEntity<String> auditExport(
            @RequestParam("claimNumber") String claimNumber) {
        Long claimId = jdbcTemplate.query(
                "SELECT id FROM claim WHERE claim_number = ?",
                (rs, rowNum) -> rs.getLong(1), claimNumber).stream().findFirst()
                .orElseThrow(ClaimNotFoundException::new);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT created_at, actor_sub, action, before::text AS before_text,
                       after::text AS after_text, rationale
                FROM audit_log
                WHERE entity_type = 'CLAIM' AND entity_id = ?
                ORDER BY id
                """,
                claimId);
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"at", "actor", "action", "before", "after", "rationale"});
        for (Map<String, Object> row : rows) {
            lines.add(new String[] {
                    textOf(row.get("created_at")),
                    textOf(row.get("actor_sub")),
                    textOf(row.get("action")),
                    textOf(row.get("before_text")),
                    textOf(row.get("after_text")),
                    textOf(row.get("rationale"))});
        }
        return csv("audit-" + claimNumber + ".csv", lines);
    }

    /**
     * Closures in range as CSV: claim, policy, product, aggregate, totals,
     * decider, rationale, denial codes. {@code from}/{@code to} are dates
     * (400 on a bad format); closures are claims with a decision whose
     * closed_at falls between from and to.
     */
    @GetMapping("/decisions/export")
    public ResponseEntity<String> decisionsExport(
            @RequestParam("from") String from, @RequestParam("to") String to) {
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } catch (RuntimeException ex) {
            throw new InvalidRequestException(
                    "from and to must be dates (yyyy-MM-dd).");
        }
        Instant fromInstant = fromDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant =
                toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT c.claim_number, p.policy_number, p.product_code,
                       p.sum_insured, c.indemnity_amount, c.decision,
                       c.decision_remarks, c.closed_at, a.actor_sub AS decider,
                       a.rationale AS decision_rationale
                FROM claim c
                JOIN policy p ON p.id = c.policy_id
                LEFT JOIN LATERAL (
                    SELECT actor_sub, rationale FROM audit_log
                    WHERE entity_type = 'CLAIM' AND entity_id = c.id
                      AND action = 'DECISION'
                    ORDER BY id DESC LIMIT 1
                ) a ON TRUE
                WHERE c.decision IS NOT NULL AND c.closed_at >= ?
                  AND c.closed_at < ?
                ORDER BY c.closed_at
                """,
                java.sql.Timestamp.from(fromInstant),
                java.sql.Timestamp.from(toInstant));
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"claim", "policy", "product", "aggregate", "totals",
                "decider", "rationale", "denialCodes"});
        for (Map<String, Object> row : rows) {
            Long claimId = jdbcTemplate.queryForObject(
                    "SELECT id FROM claim WHERE claim_number = ?",
                    Long.class, textOf(row.get("claim_number")));
            List<String> codes = jdbcTemplate.query(
                    "SELECT cover_code || '=' || denial_reason FROM claim_cover "
                            + "WHERE claim_id = ? AND decision = 'REJECTED' "
                            + "AND denial_reason IS NOT NULL ORDER BY cover_code",
                    (rs, rowNum) -> rs.getString(1), claimId);
            lines.add(new String[] {
                    textOf(row.get("claim_number")),
                    textOf(row.get("policy_number")),
                    textOf(row.get("product_code")),
                    textOf(row.get("sum_insured")),
                    textOf(row.get("indemnity_amount")),
                    textOf(row.get("decider")),
                    textOf(row.get("decision_rationale")),
                    String.join(";", codes)});
        }
        return csv("decisions-" + from + "-" + to + ".csv", lines);
    }

    private static ResponseEntity<String> csv(String filename, List<String[]> lines) {
        StringBuilder body = new StringBuilder();
        for (String[] line : lines) {
            for (int i = 0; i < line.length; i++) {
                if (i > 0) {
                    body.append(',');
                }
                body.append(cell(line[i]));
            }
            body.append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition
                        .attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body.toString());
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
