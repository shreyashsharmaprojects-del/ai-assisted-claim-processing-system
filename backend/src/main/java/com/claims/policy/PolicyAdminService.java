package com.claims.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.DuplicatePolicyNumberException;
import com.claims.api.InvalidRequestException;
import com.claims.api.PageRequest;
import com.claims.api.PageResult;
import com.claims.routing.AuthorityConfigRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * R1 policy admin: supervisor-only create / CSV import / retire / paginated admin list.
 * Policies are never deleted — claims reference them — so dead policies are RETIRED and
 * stay readable for history. The {@code policy_number} UNIQUE constraint remains the
 * duplicate guard; violations surface as a clean 409, never a 500.
 */
@Service
public class PolicyAdminService {

    static final int MAX_IMPORT_ROWS = 500;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_TEXT = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<PolicyAdminView> ROW = (rs, rowNum) -> new PolicyAdminView(
            rs.getString("policy_number"),
            rs.getString("product_code"),
            rs.getString("holder_name"),
            rs.getString("holder_email"),
            rs.getString("status"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private final PolicyRepository policies;
    private final AuthorityConfigRepository authorityConfigs;
    private final JdbcTemplate jdbcTemplate;

    public PolicyAdminService(PolicyRepository policies,
            AuthorityConfigRepository authorityConfigs, JdbcTemplate jdbcTemplate) {
        this.policies = policies;
        this.authorityConfigs = authorityConfigs;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Creates one policy; unknown product code is a 400 naming the valid codes. */
    @Transactional
    public PolicyAdminView create(CreatePolicyRequest request) {
        String policyNumber = requireText(request == null ? null : request.policyNumber(),
                "Policy number is required.");
        String productCode = normalizeProductCode(request.productCode());
        requireKnownProduct(productCode);
        String holderName = requireText(request.holderName(), "Policyholder name is required.");
        String holderEmail = requireText(request.holderEmail(), "Policyholder email is required.");
        requireEmail(holderEmail);
        String coverage = coverageOrDefault(request.coverage());

        Long id = insert(policyNumber, productCode, holderName, holderEmail, coverage);
        return adminView(id);
    }

    /**
     * Imports a CSV (header {@code policy_number,product_code,holder_name,holder_email,
     * coverage}; coverage optional raw JSON). At most 500 data rows. Each row commits on
     * its own: valid rows persist, bad rows surface as per-row errors in the summary.
     */
    public List<PolicyImportRowResult> importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("An import file is required.");
        }
        List<String[]> rows = parseCsv(file);
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new InvalidRequestException(
                    "Too many rows: the import limit is " + MAX_IMPORT_ROWS + " rows.");
        }
        List<PolicyImportRowResult> results = new ArrayList<>();
        int rowNumber = 0;
        for (String[] columns : rows) {
            rowNumber++;
            results.add(importRow(rowNumber, columns));
        }
        return results;
    }

    /** ACTIVE → RETIRED; retiring an already-RETIRED row is a no-op returning it as-is. */
    @Transactional
    public PolicyAdminView retire(String policyNumber) {
        Policy policy = policies.findByPolicyNumber(normalizePolicyNumber(policyNumber))
                .orElseThrow(com.claims.api.ClaimNotFoundException::new);
        if (!policy.isRetired()) {
            policy.setStatus("RETIRED");
            // Flush so the JDBC read below (same transaction) sees the update — the JPA
            // write-behind would otherwise return the stale ACTIVE row.
            policies.saveAndFlush(policy);
        }
        return adminView(policy.getId());
    }

    /** All rows incl. RETIRED, newest first, paginated (R4 envelope + page/size params). */
    public PageResult<PolicyAdminView> adminList(PageRequest paging) {
        String where = "";
        List<Object> filterArgs = List.of();
        if (paging.hasQuery()) {
            where = "WHERE policy_number ILIKE ? ESCAPE '\\' "
                    + "OR holder_name ILIKE ? ESCAPE '\\' "
                    + "OR product_code ILIKE ? ESCAPE '\\'";
            String like = "%" + escapeLike(paging.q()) + "%";
            filterArgs = List.of(like, like, like);
        }
        if (paging.hasStatus()) {
            where += where.isEmpty() ? "WHERE status = ?" : " AND status = ?";
            filterArgs = new ArrayList<>(filterArgs);
            filterArgs.add(paging.status());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM policy " + where, Long.class, filterArgs.toArray());
        long totalElements = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(filterArgs);
        pageArgs.add(paging.size());
        pageArgs.add(paging.offset());
        List<PolicyAdminView> content = jdbcTemplate.query(
                "SELECT policy_number, product_code, holder_name, holder_email, status, created_at "
                        + "FROM policy " + where + " ORDER BY created_at DESC, id DESC "
                        + "LIMIT ? OFFSET ?",
                ROW, pageArgs.toArray());
        return PageResult.of(content, paging, totalElements);
    }

    // --- row import (own transaction per row) -----------------------------------

    @Transactional
    public PolicyImportRowResult importRow(int rowNumber, String[] columns) {
        String policyNumber = column(columns, 0);
        try {
            if (columns.length < 4) {
                return fail(rowNumber, policyNumber,
                        "Expected at least policy_number,product_code,holder_name,holder_email.");
            }
            String number = requireText(policyNumber, "Policy number is required.");
            String productCode = normalizeProductCode(column(columns, 1));
            requireKnownProduct(productCode);
            String holderName = requireText(column(columns, 2),
                    "Policyholder name is required.");
            String holderEmail = requireText(column(columns, 3),
                    "Policyholder email is required.");
            requireEmail(holderEmail);
            String coverage = columns.length >= 5 ? coverageOrDefault(column(columns, 4)) : "{}";
            insert(number, productCode, holderName, holderEmail, coverage);
            return new PolicyImportRowResult(rowNumber, number, true, null);
        } catch (InvalidRequestException ex) {
            return fail(rowNumber, blankToNull(policyNumber), ex.getMessage());
        } catch (DuplicatePolicyNumberException ex) {
            return fail(rowNumber, blankToNull(policyNumber), ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            return fail(rowNumber, blankToNull(policyNumber),
                    "Duplicate policy number: it already exists.");
        }
    }

    private Long insert(String policyNumber, String productCode, String holderName,
            String holderEmail, String coverage) {
        String normalized = normalizePolicyNumber(policyNumber);
        if (policies.findByPolicyNumber(normalized).isPresent()) {
            throw new DuplicatePolicyNumberException(
                    "Policy number " + normalized + " already exists.");
        }
        try {
            return jdbcTemplate.queryForObject(
                    "INSERT INTO policy (policy_number, product_code, holder_name, holder_email, "
                            + "coverage) VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id",
                    Long.class, normalized, productCode, holderName.trim(),
                    holderEmail.trim(), coverage);
        } catch (DataIntegrityViolationException ex) {
            // Race between the pre-check and the insert: the UNIQUE constraint is the real
            // guard; surface the clean duplicate message, never driver internals.
            throw new DuplicatePolicyNumberException(
                    "Policy number " + normalized + " already exists.");
        }
    }

    private PolicyAdminView adminView(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT policy_number, product_code, holder_name, holder_email, status, created_at "
                        + "FROM policy WHERE id = ?",
                ROW, id);
    }

    // --- CSV parsing (small, dependency-free; coverage JSON may not contain commas
    //     unquoted — quoted fields with embedded commas/quotes are supported) ----------

    private static List<String[]> parseCsv(MultipartFile file) {
        String text;
        try {
            text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new InvalidRequestException("The import file could not be read.");
        }
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new InvalidRequestException(
                    "The import file is empty: expected the header "
                            + "policy_number,product_code,holder_name,holder_email,coverage.");
        }
        List<String> header = splitLine(lines[0]);
        if (header.size() < 4
                || !header.get(0).trim().equalsIgnoreCase("policy_number")
                || !header.get(1).trim().equalsIgnoreCase("product_code")
                || !header.get(2).trim().equalsIgnoreCase("holder_name")
                || !header.get(3).trim().equalsIgnoreCase("holder_email")) {
            throw new InvalidRequestException(
                    "Bad header: expected "
                            + "policy_number,product_code,holder_name,holder_email,coverage.");
        }
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            rows.add(splitLine(lines[i]).toArray(String[]::new));
        }
        return rows;
    }

    private static List<String> splitLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    // --- validation helpers ------------------------------------------------------

    private void requireKnownProduct(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            throw new InvalidRequestException("Product code is required.");
        }
        List<String> valid = authorityConfigs.findAll().stream()
                .map(c -> c.getProductCode()).sorted().toList();
        if (!valid.contains(productCode)) {
            throw new InvalidRequestException("Unknown product code '" + productCode
                    + "'. Valid codes: " + String.join(", ", valid) + ".");
        }
    }

    private static String normalizeProductCode(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePolicyNumber(String raw) {
        if (raw == null) {
            throw new InvalidRequestException("Policy number is required.");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(message);
        }
        if (value.trim().length() > MAX_TEXT) {
            throw new InvalidRequestException(message + " (too long).");
        }
        return value.trim();
    }

    private static void requireEmail(String email) {
        if (email.length() > MAX_TEXT || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new InvalidRequestException(
                    "Policyholder email must be a valid email address.");
        }
    }

    private static String coverageOrDefault(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        if (!node.isObject()) {
            throw new InvalidRequestException(
                    "Coverage must be a JSON object string.");
        }
        return node.toString();
    }

    private static String coverageOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        try {
            JsonNode node = JSON.readTree(trimmed);
            if (!node.isObject()) {
                throw new InvalidRequestException(
                        "Coverage must be a JSON object string.");
            }
        } catch (tools.jackson.core.JacksonException ex) {
            throw new InvalidRequestException("Coverage must be a JSON object string.");
        }
        return trimmed;
    }

    private static String column(String[] columns, int index) {
        return index < columns.length ? columns[index].trim() : null;
    }

    private static PolicyImportRowResult fail(int row, String policyNumber, String error) {
        return new PolicyImportRowResult(row, policyNumber, false, error);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
