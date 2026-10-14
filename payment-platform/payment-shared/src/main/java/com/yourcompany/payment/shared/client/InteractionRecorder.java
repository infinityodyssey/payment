package com.yourcompany.payment.shared.client;

import com.yourcompany.payment.shared.entity.LmsApiLog;
import com.yourcompany.payment.shared.repo.LmsApiLogRepository;
import com.yourcompany.payment.shared.util.Mask;
import com.yourcompany.payment.shared.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Records every upstream call to LMS_API_LOG.
 *
 * Two places, two purposes: Oracle holds full request and response bodies for
 * reconciliation and disputes; the application log carries metadata only — endpoint,
 * correlation id, duration, status, outcome. An LMS response contains the borrower's
 * name, mobile number and dues, and a log aggregator's retention and access model are
 * set by whoever runs the platform, not by you.
 *
 * The write is async and must never fail the caller's work. Failing a payment posting
 * because an audit insert failed is the worse outcome. Alert on INTERACTION RECORD
 * FAILED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionRecorder {

    private final LmsApiLogRepository repository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String correlationId, Interaction it) {
        try {
            repository.save(LmsApiLog.builder()
                    .correlationId(correlationId)
                    .txnReference(it.txnReference())
                    .attemptNo(it.attemptNo())
                    .apiName(it.apiName())
                    .endpoint(truncate(it.endpoint(), 512))
                    .httpMethod(it.method())
                    .agreementNumber(it.agreementNumber())
                    .requestBody(it.requestBody())
                    .responseBody(it.responseBody())
                    .httpStatus(it.httpStatus())
                    .upstreamStatus(it.upstreamStatus())
                    .upstreamCode(it.upstreamCode())
                    .outcome(it.outcome())
                    .errorDetail(truncate(it.errorDetail(), 512))
                    .durationMs(it.durationMs())
                    .requestedAt(it.requestedAt())
                    .respondedAt(it.respondedAt())
                    .build());
        } catch (Exception e) {
            log.error("INTERACTION RECORD FAILED api={} correlationId={} cause={}",
                    it.apiName(), correlationId, e.getClass().getSimpleName());
        }

        log.info("upstream call api={} endpoint={} status={} outcome={} durationMs={} agreement={}",
                it.apiName(), it.endpoint(), it.httpStatus(), it.outcome(),
                it.durationMs(), Mask.agreement(it.agreementNumber()));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Immutable carrier so nothing request-scoped crosses the async boundary. */
    public record Interaction(
            String apiName,
            String endpoint,
            String method,
            String agreementNumber,
            String txnReference,
            Integer attemptNo,
            String requestBody,
            String responseBody,
            Integer httpStatus,
            String upstreamStatus,
            String upstreamCode,
            String outcome,
            String errorDetail,
            Long durationMs,
            Instant requestedAt,
            Instant respondedAt) {

        /** Authentication-phase calls: no transaction, no attempt number. */
        public static Interaction of(String apiName, String endpoint, String method,
                                     String agreementNumber, String requestBody, String responseBody,
                                     Integer httpStatus, String upstreamStatus, String upstreamCode,
                                     String outcome, String errorDetail, Instant requestedAt) {
            return of(apiName, endpoint, method, agreementNumber, null, null,
                    requestBody, responseBody, httpStatus, upstreamStatus, upstreamCode,
                    outcome, errorDetail, requestedAt);
        }

        /** Posting-phase calls: carries the transaction reference and attempt number. */
        public static Interaction of(String apiName, String endpoint, String method,
                                     String agreementNumber, String txnReference, Integer attemptNo,
                                     String requestBody, String responseBody,
                                     Integer httpStatus, String upstreamStatus, String upstreamCode,
                                     String outcome, String errorDetail, Instant requestedAt) {
            Instant now = Instant.now();
            return new Interaction(apiName, endpoint, method, agreementNumber, txnReference, attemptNo,
                    requestBody, responseBody, httpStatus, upstreamStatus, upstreamCode,
                    outcome, errorDetail,
                    Duration.between(requestedAt, now).toMillis(), requestedAt, now);
        }

        public static String correlation() {
            return CorrelationIdFilter.current();
        }
    }
}
