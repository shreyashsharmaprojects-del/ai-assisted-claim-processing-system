package com.claims.claim;

import org.springframework.stereotype.Component;

import com.claims.staff.AppUserRepository;

/**
 * Authorization for per-claim endpoints: a claim is visible to the supervisor, or to the
 * adjuster it is assigned to (matched by JWT subject through the staff cache). Callers
 * turn "may not see" into a not-found response, so nothing ever reveals that a claim
 * number exists (the plan's 404-not-403 rule).
 */
@Component
public class ClaimAccess {

    private final AppUserRepository appUsers;

    public ClaimAccess(AppUserRepository appUsers) {
        this.appUsers = appUsers;
    }

    public boolean internalReaderMaySee(Claim claim, String jwtSubject, boolean supervisor) {
        if (supervisor) {
            return true;
        }
        if (claim.getAssignedAdjusterId() == null) {
            return false;
        }
        return appUsers.findById(claim.getAssignedAdjusterId())
                .map(adjuster -> adjuster.getKeycloakSub().equals(jwtSubject))
                .orElse(false);
    }
}
