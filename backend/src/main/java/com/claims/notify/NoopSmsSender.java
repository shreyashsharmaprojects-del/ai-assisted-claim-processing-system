package com.claims.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * V25 (V3 S10): the SMS no-op — logs at info, records nothing, sends nothing.
 * The only {@link SmsSender} bean, so any future call site resolves safely.
 * A provider adapter replaces this when SMS ships (set
 * {@code claims.sms.enabled=true} then); no other code changes.
 */
@Service
public class NoopSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(NoopSmsSender.class);

    @Override
    public void send(String phoneNumber, String message) {
        log.info("SMS sending is disabled; dropping an SMS (no record kept).");
    }
}
