package com.claims.staff;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V3 S7: the supervisor's staff surface — who exists, who's loaded, and the
 * active toggle. SecurityConfig admits SUPERVISOR only at the URL; an unknown
 * staff id is a 404. No Keycloak writes this slice.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public List<StaffService.StaffRow> list() {
        return staffService.list();
    }

    @PutMapping("/{id}/active")
    public StaffService.StaffRow setActive(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id, @RequestBody ActiveToggle toggle) {
        if (toggle == null || toggle.active() == null) {
            throw new com.claims.api.InvalidRequestException(
                    "The request must carry an 'active' boolean.");
        }
        return staffService.setActive(id, toggle.active(), jwt.getSubject());
    }

    /** The active toggle; missing/null bodies are rejected as 400 upstream. */
    public record ActiveToggle(Boolean active) {
    }
}
