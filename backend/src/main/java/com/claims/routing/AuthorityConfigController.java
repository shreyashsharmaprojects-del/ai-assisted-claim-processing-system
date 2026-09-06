package com.claims.routing;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authority config surface (slice 7, route-table row {@code /admin/authority}): the
 * supervisor reads every product's routing/threshold row and edits it. URL-level rules in
 * SecurityConfig admit SUPERVISOR only. The edited values feed the authority gate (next
 * decision) and the classifier (next FNOL) immediately — no caching (see the service).
 */
@RestController
@RequestMapping("/api/config/authority")
public class AuthorityConfigController {

    private final AuthorityConfigService configService;

    public AuthorityConfigController(AuthorityConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public List<AuthorityConfigView> list() {
        return configService.list();
    }

    @PutMapping("/{productCode}")
    public AuthorityConfigView update(@PathVariable String productCode,
            @RequestBody UpdateRequest request) {
        return configService.update(productCode, request.routeLevel(), request.l1LimitAmount(),
                request.l2LimitAmount());
    }

    /** The three editable parameters; the product code is the path variable. */
    public record UpdateRequest(String routeLevel, BigDecimal l1LimitAmount,
            BigDecimal l2LimitAmount) {
    }
}
