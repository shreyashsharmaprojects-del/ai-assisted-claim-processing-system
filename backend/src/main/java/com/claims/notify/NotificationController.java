package com.claims.notify;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claims.notify.NotificationService.NotificationPage;
import com.claims.notify.NotificationService.NotificationPreferencesView;
import com.claims.notify.NotificationService.NotificationView;
import com.claims.notify.NotificationService.PreferenceInput;

/**
 * V25 (V3 S10): the claimant's own notification center — paginated in-app feed
 * (with the bell unread count on the envelope), mark-read, and the preference
 * round-trip. URL roles live in {@code SecurityConfig} (CLAIMANT); own-sub is
 * enforced in the service (another claimant's row id is a 404, never a 403).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** Own notifications, newest first; the envelope carries {@code unread}. */
    @GetMapping("/mine")
    public NotificationPage mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return notifications.mine(jwt.getSubject(), page == null ? 0 : page,
                size == null ? 25 : size);
    }

    /** Marks one own notification read (idempotent; another's id is a 404). */
    @PostMapping("/{id}/read")
    public NotificationView markRead(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        return notifications.markRead(id, jwt.getSubject());
    }

    /** Own preference row (defaults when never saved). */
    @GetMapping("/preferences")
    public NotificationPreferencesView preferences(
            @AuthenticationPrincipal Jwt jwt) {
        return notifications.preferences(jwt.getSubject());
    }

    /** Upserts the caller's own preference row. */
    @PutMapping("/preferences")
    public NotificationPreferencesView savePreferences(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PreferenceInput input) {
        return notifications.savePreferences(jwt.getSubject(), input);
    }
}
