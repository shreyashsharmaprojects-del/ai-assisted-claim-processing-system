package com.claims.claim;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Small authority helpers shared by internal controllers. */
public final class Authorities {

    private Authorities() {
    }

    public static boolean isSupervisor(Authentication authentication) {
        return authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_SUPERVISOR"));
    }
}
