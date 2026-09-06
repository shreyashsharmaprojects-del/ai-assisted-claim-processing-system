package com.claims.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * FNOL notification email. Best-effort by design: a mail outage must never lose a claim,
 * so send failures are logged, not thrown (see docs/decisions.md).
 */
@Component
public class FnolEmailSender {

    private static final Logger log = LoggerFactory.getLogger(FnolEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public FnolEmailSender(JavaMailSender mailSender, @Value("${claims.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendFnolConfirmation(String to, String claimNumber, String holderName) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Claim " + claimNumber + " received");
        message.setText("""
                Dear %s,

                We have received your claim %s.

                What happens next: your claim is being routed to an adjuster, who will
                review the details and get in touch. You can track progress on your
                claim status screen.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber));
        try {
            mailSender.send(message);
        } catch (RuntimeException ex) {
            log.error("Failed to send FNOL email for claim {}", claimNumber, ex);
        }
    }
}
