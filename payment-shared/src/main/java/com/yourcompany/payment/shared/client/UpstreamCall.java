package com.yourcompany.payment.shared.client;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * One outbound HTTP call, described rather than executed.
 *
 * Everything an upstream call needs to be sent, classified and audited lives here, so
 * {@link UpstreamHttpClient} can do all of those things in one place for every API.
 * Adding the next LMS endpoint means building one of these, not writing another
 * RestClient block with its own timing, logging and error handling.
 *
 * <h2>upstream</h2>
 * A single name drives three lookups, and they must stay in step:
 * <ul>
 *   <li>the RestClient bean, named {@code <upstream>RestClient} - connect and read
 *       timeouts live there;</li>
 *   <li>{@code resilience4j.bulkhead.instances.<upstream>};</li>
 *   <li>{@code resilience4j.circuitbreaker.instances.<upstream>}.</li>
 * </ul>
 * That is why resilience is applied here programmatically rather than with
 * {@code @Bulkhead(name = "lms")}. An annotation name is a compile-time constant, so a
 * shared transport could never be "lms" for one caller and "sms" for another. It also
 * sidesteps the AOP self-invocation trap: a proxied annotation is silently skipped when
 * one method of a class calls another on {@code this}, which is exactly what a merged
 * client class does when a business call fetches its own bearer token.
 */
@Getter
@Builder
public class UpstreamCall {

    /** Selects the RestClient bean and the resilience instances. See the class note. */
    private final String upstream;

    /** Goes to LMS_API_LOG.API_NAME. Use a constant from {@link ApiName}. */
    private final String apiName;

    /** Absolute URL. Already encoded: it is converted with URI.create, not expanded as a template. */
    private final String url;

    /**
     * Headers for this call.
     *
     * Each client assembles its own common set once - for LMS that is {@code tkn} and
     * {@code fiid} - and merges whatever the call site adds on top.
     */
    @Singular
    private final Map<String, String> headers;

    /** Serialised to JSON by the transport. Null for a GET, or for a POST with no body. */
    private final Object body;

    private final String agreementNumber;
    private final String txnReference;
    private final Integer attemptNo;

    /**
     * Turns the response into the audit verdict.
     *
     * Classification runs outside the circuit breaker on purpose. A "no such agreement"
     * response is a business answer, not an upstream fault, and must not push the
     * breaker towards open.
     */
    @Builder.Default
    private final Function<UpstreamResponse, UpstreamOutcome> classifier = UpstreamOutcome::fromLmsEnvelope;

    /**
     * Applied to the endpoint and the response body before they are persisted.
     *
     * The SMS gateway puts the one-time code in the query string, so that call passes a
     * redactor. Default is identity.
     */
    @Builder.Default
    private final UnaryOperator<String> redactor = value -> value;

    /** False where the body would put a credential in the audit table - a token response. */
    @Builder.Default
    private final boolean recordRequestBody = true;

    @Builder.Default
    private final boolean recordResponseBody = true;

    /** The exception the borrower's request fails with when this call cannot complete. */
    private final Supplier<? extends RuntimeException> failure;
}
