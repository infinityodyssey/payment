package com.yourcompany.payment.shared.client;

import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.UpstreamException;
import com.yourcompany.payment.shared.exception.UpstreamStatusException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The only place in this platform that performs an outbound HTTP call.
 *
 * Every LMS and SMS API goes through {@link #get} or {@link #post}. That gives one
 * implementation of the five things that were previously copied into each client class:
 *
 * <ol>
 *   <li><b>Connection</b> - the RestClient for the upstream, with its timeouts, is
 *       resolved by name. No client builds its own.</li>
 *   <li><b>Resilience</b> - the bulkhead and circuit breaker for that upstream are
 *       applied around the wire call.</li>
 *   <li><b>Timing</b> - one clock, started before the request and stopped after the
 *       response, so durations in LMS_API_LOG are comparable across every API.</li>
 *   <li><b>Audit</b> - one write to LMS_API_LOG, with the same columns populated the
 *       same way whichever API was called.</li>
 *   <li><b>Failure translation</b> - a timeout, a refused connection, a full bulkhead
 *       and an open circuit all become the upstream's controlled error. The borrower
 *       sees one message; the distinction survives in the metrics and the error
 *       detail column.</li>
 * </ol>
 *
 * <h2>Why resilience is wired here and not with annotations</h2>
 * Two reasons, both of which would have produced a silent hole.
 *
 * {@code @Bulkhead} and {@code @CircuitBreaker} work through an AOP proxy, and a proxy
 * is only involved when the call arrives from outside the bean. Consolidating the LMS
 * APIs into one class means a business method calls the token method on {@code this};
 * that self-invocation skips the proxy entirely, so the token call would have run with
 * no bulkhead and no breaker. You would discover it the day the LMS token endpoint hung.
 * Here the transport is a separate bean and the decoration is explicit, so it applies no
 * matter who called whom.
 *
 * And an annotation's {@code name} is a compile-time constant. A single shared transport
 * cannot be {@code name = "lms"} for one caller and {@code "sms"} for another. Reading
 * the instance out of the registry per call is what lets one transport serve every
 * upstream with that upstream's own limits.
 *
 * <h2>Ordering</h2>
 * Bulkhead outside, breaker inside. The breaker needs a window of samples before it
 * trips; with a 30 second LMS read timeout that window is long enough for every
 * in-flight request to pile onto a degraded upstream, and virtual threads are off, so
 * nothing else bounds it either. The bulkhead refuses the call over the limit
 * immediately, which keeps OTP verification and status polling answering while the LMS
 * is slow. A rejection by the bulkhead is not fed to the breaker as a failure - it is
 * our own back pressure, not evidence about the upstream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamHttpClient {

    /** Bean name -> client. A call names its upstream; the bean is {@code <upstream>RestClient}. */
    private final Map<String, RestClient> restClients;
    private final ObjectMapper mapper;
    private final BulkheadRegistry bulkheads;
    private final CircuitBreakerRegistry circuitBreakers;
    private final InteractionRecorder recorder;

    public UpstreamResponse get(UpstreamCall call) {
        return execute(call, HttpMethod.GET);
    }

    public UpstreamResponse post(UpstreamCall call) {
        return execute(call, HttpMethod.POST);
    }

    private UpstreamResponse execute(UpstreamCall call, HttpMethod method) {
        String correlationId = InteractionRecorder.Interaction.correlation();
        String endpoint = call.getRedactor().apply(call.getUrl());
        String serialisedBody = serialise(call.getBody());
        String requestBody = call.isRecordRequestBody() ? serialisedBody : null;
        Instant requestedAt = Instant.now();

        try {
            // Everything inside this supplier - and nothing outside it - is what the
            // bulkhead bounds and the breaker measures.
            ResponseEntity<String> entity = guard(call.getUpstream(),
                    () -> send(call, method, serialisedBody));

            UpstreamResponse response = new UpstreamResponse(
                    entity.getStatusCode().value(), entity.getBody(), parse(entity.getBody()));

            UpstreamOutcome outcome = call.getClassifier().apply(response);

            record(correlationId, call, method, endpoint, requestBody,
                    call.isRecordResponseBody()
                            ? call.getRedactor().apply(response.raw())
                            : null,
                    response.httpStatus(), outcome, requestedAt);

            return response;

        } catch (ApiException e) {
            // Already a controlled failure with a caller-safe message. Record and pass on.
            record(correlationId, call, method, endpoint, requestBody, null, null,
                    UpstreamOutcome.transportFailure(e.getErrorCode()), requestedAt);
            throw e;

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.error("{} returned HTTP {} for {}", call.getUpstream(), status, call.getApiName());
            record(correlationId, call, method, endpoint, requestBody,
                    call.isRecordResponseBody()
                            ? call.getRedactor().apply(e.getResponseBodyAsString())
                            : null,
                    status, UpstreamOutcome.transportFailure("http " + status), requestedAt);
            throw new UpstreamStatusException(upstreamFailure(call), status);

        } catch (Exception e) {
            // Timeout, DNS, refused connection, full bulkhead, open circuit, malformed
            // response. The class name is the only diagnostic that goes to the log; the
            // borrower gets the upstream's standard message.
            String detail = e.getClass().getSimpleName();
            log.error("{} call {} failed: {}", call.getUpstream(), call.getApiName(), detail);
            record(correlationId, call, method, endpoint, requestBody, null, null,
                    UpstreamOutcome.transportFailure(detail), requestedAt);
            throw upstreamFailure(call);
        }
    }

    private ResponseEntity<String> send(UpstreamCall call, HttpMethod method, String body) {
        RestClient client = restClients.get(call.getUpstream() + "RestClient");
        if (client == null) {
            // A wiring mistake, not a runtime condition. Fail loudly: the alternative is
            // a service that looks healthy until the first call to that upstream.
            throw new IllegalStateException(
                    "No RestClient bean named " + call.getUpstream() + "RestClient");
        }

        // URI, not a String: a String uri() is treated as a template, and an SMS message
        // or an LMS payload containing braces would be mangled or rejected.
        RestClient.RequestBodySpec spec = client.method(method).uri(URI.create(call.getUrl()));
        call.getHeaders().forEach((name, value) -> {
            if (value != null) {
                spec.header(name, value);
            }
        });

        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    /** Bulkhead outside, circuit breaker inside. See the class note on ordering. */
    private <T> T guard(String upstream, Supplier<T> supplier) {
        Bulkhead bulkhead = bulkheads.bulkhead(upstream);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(upstream);
        return Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(breaker, supplier)).get();
    }

    private void record(String correlationId, UpstreamCall call, HttpMethod method,
                        String endpoint, String requestBody, String responseBody,
                        Integer httpStatus, UpstreamOutcome outcome, Instant requestedAt) {
        recorder.record(correlationId, InteractionRecorder.Interaction.of(
                call.getApiName(), endpoint, method.name(),
                call.getAgreementNumber(), call.getTxnReference(), call.getAttemptNo(),
                requestBody, responseBody, httpStatus,
                outcome.upstreamStatus(), outcome.upstreamCode(),
                outcome.outcome(), outcome.errorDetail(), requestedAt));
    }

    private String serialise(Object body) {
        if (body == null) {
            return null;
        }
        return body instanceof String s ? s : mapper.writeValueAsString(body);
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (RuntimeException e) {
            // A non-JSON body is an upstream problem to diagnose from the stored payload,
            // not a reason to lose the response. Callers see no fields and classify it.
            log.warn("Upstream response was not JSON: {}", e.getClass().getSimpleName());
            return mapper.createObjectNode();
        }
    }

    private UpstreamException upstreamFailure(UpstreamCall call) {
        if (call.getFailure() == null) {
            return UpstreamException.lms();
        }
        RuntimeException failure = call.getFailure().get();
        return failure instanceof UpstreamException e ? e : UpstreamException.lms();
    }
}
