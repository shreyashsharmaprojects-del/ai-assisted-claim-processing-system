package com.claims.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Assignment notification email (slice 2): the claimant is told their claim is now with an
 * adjuster and who to contact. Best-effort by design, like the FNOL email — a mail outage
 * must never lose a claim, so send failures are logged, not thrown.
 */
@Component
public class AssignmentEmailSender {

    private static final Logger log = LoggerFactory.getLogger(AssignmentEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public AssignmentEmailSender(JavaMailSender mailSender,
            @Value("${claims.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendAssignment(String to, String claimNumber, String holderName,
            String adjusterName, String adjusterEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Claim " + claimNumber + " is now with an adjuster");
        message.setText("""
                Dear %s,

                Your claim %s has been assigned to an adjuster:

                  %s (%s)

                They have your details and photos and will review the claim and be in
                touch with you. You can track progress on your claim status screen.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber, adjusterName, adjusterEmail));
        try {
            mailSender.send(message);
        } catch (RuntimeException ex) {
            log.error("Failed to send assignment email for claim {}", claimNumber, ex);
        }
    }
}
