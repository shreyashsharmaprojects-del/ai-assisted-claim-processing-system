package com.claims.policy;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * R1 policy admin (supervisor-only via SecurityConfig): create, CSV import with per-row
 * results, retire, and the paginated admin list (all rows incl. RETIRED, newest first).
 * The legacy {@code GET /api/policies} full-book list is supervisor-only too
 * (see {@link PolicyController}) — never a claimant/adjuster surface.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyAdminController {

    private final PolicyAdminService adminService;

    public PolicyAdminController(PolicyAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping
    public PolicyAdminView create(@RequestBody CreatePolicyRequest request) {
        return adminService.create(request);
    }

    /**
     * CSV import: multipart {@code file}, at most 500 data rows. Always HTTP 200 with the
     * per-row summary ({@code {row,policyNumber,ok,error}}); only a bad file shape or an
     * over-limit file is a 400 — never a bare 500 on row 400.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<PolicyImportRowResult> importCsv(@RequestParam("file") MultipartFile file) {
        return adminService.importCsv(file);
    }

    @PostMapping("/{policyNumber}/retire")
    public PolicyAdminView retire(@PathVariable String policyNumber) {
        return adminService.retire(policyNumber);
    }

    @GetMapping("/admin")
    public PageResult<PolicyAdminView> admin(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status) {
        return adminService.adminList(PageRequest.of(page, size, q, status));
    }
}
