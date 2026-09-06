package com.claims.mail;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.claims.claim.ClaimDecisionView;

/**
 * Decision notification email (slice 4): the claimant is told their claim was approved
 * (with the amount) or denied (with the remarks). Best-effort by design, like the FNOL and
 * assignment emails — a mail outage must never roll back a decision, so send failures are
 * logged, not thrown.
 */
@Component
public class DecisionEmailSender {

    private static final Logger log = LoggerFactory.getLogger(DecisionEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public DecisionEmailSender(JavaMailSender mailSender,
            @Value("${claims.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendDecision(String to, String holderName, ClaimDecisionView decision) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Decision on claim " + decision.claimNumber());
        message.setText(body(holderName, decision));
        try {
            mailSender.send(message);
        } catch (RuntimeException ex) {
            log.error("Failed to send decision email for claim {}", decision.claimNumber(), ex);
        }
    }

    private static String body(String holderName, ClaimDecisionView decision) {
        if ("APPROVED".equals(decision.decision())) {
            return """
                    Dear %s,

                    Your claim %s has been approved. We will pay %s.

                    Yours,
                    Claims Processing
                    """.formatted(holderName, decision.claimNumber(), pounds(decision.indemnityAmount()));
        }
        return """
                Dear %s,

                Your claim %s has not been approved.

                %s

                Yours,
                Claims Processing
                """.formatted(holderName, decision.claimNumber(),
                decision.decisionRemarks() == null ? "" : decision.decisionRemarks());
    }

    private static String pounds(BigDecimal amount) {
        return amount == null ? "" : "\u00a3" + amount.toPlainString();
    }
}
