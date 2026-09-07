package com.claims.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled shell for the outbox dispatcher (R2). Runs every 60s with the real clock.
 * Integration tests disable this component ({@code claims.outbox.enabled=false} via the
 * inherited @DynamicPropertySource on ClaimTableResettingTest) and drive
 * {@link EmailOutboxDispatcher#dispatch()} directly instead — the same pattern as the
 * aging scheduler. The dispatcher bean itself stays in context (controllers flush it
 * after commit), so delivery assertions observe SENT rows without the cron.
 */
@Component
@ConditionalOnProperty(name = "claims.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class EmailOutboxScheduler {

    private final EmailOutboxDispatcher dispatcher;

    public EmailOutboxScheduler(EmailOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${claims.outbox.interval-ms:60000}")
    public void runOutboxPass() {
        dispatcher.dispatch();
    }
}
