package com.claims.notify;

/**
 * V25 (V3 S10): the SMS seam. A future provider adapter implements this
 * interface; nothing calls it this slice (the writer sites compose outbox +
 * in-app only). The flag {@code claims.sms.enabled=false} documents the
 * default; the Noop below ignores it and sends nothing.
 */
public interface SmsSender {

    /** Sends one SMS. Never called in S10 (Noop only, records nothing). */
    void send(String phoneNumber, String message);
}
