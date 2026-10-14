package com.yourcompany.payment.shared.web;

import com.yourcompany.payment.shared.entity.AuditEvent;
import com.yourcompany.payment.shared.repo.AuditEventRepository;
import com.yourcompany.payment.shared.util.Hashing;
import com.yourcompany.payment.shared.web.ClientContext;
import com.yourcompany.payment.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes the regulatory audit trail.
 *
 * Synchronous and transactional, unlike the interaction recorder. The reasoning: an
 * interaction row is operational and losing one costs a debugging session, whereas an
 * audit row is the evidence that an authentication happened. An async write can be
 * dropped on pod shutdown, so this one runs on the request thread and its cost is one
 * insert against a pool sized for it.
 *
 * REQUIRES_NEW so the ledger entry survives even if the surrounding work rolls back:
 * a failed OTP verification must still leave a record that it was attempted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final ClientContext clientContext;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String outcome, String errorCode,
                       String sessionId, String agreementNumber, HttpServletRequest request) {
        try {
            String userAgent = request == null ? null : request.getHeader("User-Agent");
            repository.save(AuditEvent.builder()
                    .correlationId(CorrelationIdFilter.current())
                    .eventType(eventType)
                    .outcome(outcome)
                    .errorCode(errorCode)
                    .sessionRef(sessionId == null ? null : Hashing.shortHash(sessionId))
                    .agreementNumber(agreementNumber)
                    .clientIpHash(request == null ? null : clientContext.hashIp(request))
                    .userAgent(userAgent == null ? null : userAgent.substring(0, Math.min(256, userAgent.length())))
                    .occurredAt(Instant.now())
                    .build());
        } catch (Exception e) {
            // An audit write failure is itself a reportable control failure. Alert on
            // this line. The borrower's request is not failed for it.
            log.error("AUDIT WRITE FAILED type={} correlationId={} cause={}",
                    eventType, CorrelationIdFilter.current(), e.getClass().getSimpleName());
        }
    }
}
