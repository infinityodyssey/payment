package com.yourcompany.payment.shared.client;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.dto.LoanDetails;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.UpstreamException;
import com.yourcompany.payment.shared.exception.UpstreamStatusException;
import com.yourcompany.payment.shared.util.Mask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Every LMS API, in one class.
 *
 * What lives here: which URL, which headers, which body, and how to read the answer.
 * What does not: anything to do with HTTP. The connection, the bulkhead, the circuit
 * breaker, the timing, the LMS_API_LOG write and the failure translation all belong to
 * {@link UpstreamHttpClient} and are written once there. Adding the next LMS endpoint is
 * one method on this class.
 *
 * <h2>Headers</h2>
 * {@link #headers} is the single assembly point. Every business call gets {@code tkn}
 * and {@code fiid}; a call site that needs more passes them in and they are merged on
 * top. The token endpoint is the one exception and says so where it is built - it cannot
 * send a {@code tkn} it is about to fetch.
 *
 * <h2>Token handling</h2>
 * The bearer is cached cluster-wide in Redis by {@link UpstreamTokenCache}, at a TTL
 * shorter than its real lifetime so it is never used in its last moments. If the LMS
 * rejects it anyway - 401 or 403 - {@link #withFreshToken} evicts and retries exactly
 * once. Once, not in a loop: a credential the LMS keeps refusing is a configuration
 * problem, and hammering their token endpoint will not fix it.
 *
 * <h2>Known seams</h2>
 * The agreement fetch contract below is taken from the legacy getGoldDueDetails and is
 * accurate. The token request shape, and the bodies of the three posting calls, were
 * never shared. Each one is marked SEAM and is the obvious reading of its call site;
 * replace only the request body map and the field names read from the response. Nothing
 * around them changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LmsApiClient implements LmsClient {

    /** Names the RestClient bean, the bulkhead and the circuit breaker. All three. */
    private static final String UPSTREAM = "lms";

    private final UpstreamHttpClient http;
    private final UpstreamTokenCache tokenCache;
    private final ObjectMapper mapper;
    private final SharedProperties props;

    // ================================================================ auth phase

    @Override
    public Optional<LoanDetails> findByAgreementNumber(String agreementNumber) {
        return lookupWithPayload(agreementNumber).map(AgreementLookup::details);
    }

    /**
     * Gold-loan agreement lookup, ported from the legacy getGoldDueDetails.
     *
     * Contract as observed in the existing implementation: POST to the fetch URL with
     * headers {@code tkn} and {@code fiid}, body {fiid, accountnumber, vehiclenumber},
     * always HTTP 200, success is {@code status == "Success"} with data at
     * {@code response.success[]}.
     *
     * Only four fields are read: customername, mobilenumber, accountnumber and
     * accountStatus. The dues arithmetic, address handling, charge breakdowns and the
     * loss-amount branch belong to the payment phase, and nothing is lost by deferring
     * them - the full payload is persisted to LMS_API_LOG and cached as the session
     * snapshot.
     */
    @Override
    public Optional<AgreementLookup> lookupWithPayload(String agreementNumber) {
        UpstreamResponse response = withFreshToken(() -> http.post(
                call(ApiName.AGREEMENT_FETCH, props.getGlobal().getLmsFetchUrl())
                        .agreementNumber(agreementNumber)
                        .headers(headers())
                        .body(Map.of(
                                "fiid", props.getGlobal().getFiid(),
                                "accountnumber", agreementNumber,
                                // The legacy call also supports registration-number
                                // lookup. This portal takes an agreement number only, so
                                // the field is present and empty.
                                "vehiclenumber", ""))
                        // A missing agreement is a business answer, not an upstream
                        // fault. Classified as NOT_FOUND so it reads correctly in
                        // LMS_API_LOG and never pushes the breaker towards open.
                        .classifier(r -> "Success".equalsIgnoreCase(r.text("status"))
                                ? UpstreamOutcome.success(r.text("status"), r.text("code"))
                                : UpstreamOutcome.notFound(r.text("status"), r.text("code"),
                                        "agreement not returned"))
                        .build()));

        if (!"Success".equalsIgnoreCase(response.text("status"))) {
            return Optional.empty();
        }

        JsonNode successArray = response.at("response", "success");
        if (!successArray.isArray() || successArray.isEmpty()) {
            log.warn("LMS returned Success with an empty array for {}", Mask.agreement(agreementNumber));
            return Optional.empty();
        }

        LoanDetails details = extract(successArray, agreementNumber);

        // The raw success[0] travels with the mapped details so auth-service can store it
        // as a session snapshot and fetch-service can map dues from it later without a
        // second call.
        return Optional.of(new AgreementLookup(details,
                mapper.writeValueAsString(successArray.get(0))));
    }

    // ============================================================= posting phase

    /**
     * SEAM. Request shape not supplied.
     *
     * The call site is known - this runs after the agreement fetch and before the post,
     * and its answer decides the applicable rule - so the body carries the agreement.
     * Replace the map if your endpoint expects more.
     */
    @Override
    public JsonNode otsAmount(String agreementNumber, String txnReference, Integer attemptNo) {
        return withFreshToken(() -> http.post(
                call(ApiName.OTS_AMOUNT, props.getGlobal().getLmsOtsUrl())
                        .agreementNumber(agreementNumber)
                        .txnReference(txnReference)
                        .attemptNo(attemptNo)
                        .headers(headers())
                        .body(Map.of(
                                "fiid", props.getGlobal().getFiid(),
                                "accountnumber", agreementNumber))
                        .build())).json();
    }

    /**
     * SEAM. Request shape not supplied.
     *
     * Keyed on our transaction reference, which is the one identifier both sides agree
     * on. Returned as-is: the caller decides what "already posted" looks like, because
     * that judgement belongs to the posting state machine, not to a transport class.
     */
    @Override
    public JsonNode statusCheck(String txnReference, Integer attemptNo) {
        return withFreshToken(() -> http.post(
                call(ApiName.STATUS_CHECK, props.getGlobal().getLmsStatusUrl())
                        .txnReference(txnReference)
                        .attemptNo(attemptNo)
                        .headers(headers())
                        .body(Map.of(
                                "fiid", props.getGlobal().getFiid(),
                                "tvstxnid", txnReference))
                        .build())).json();
    }

    /**
     * SEAM. Request shape not supplied.
     *
     * Our transaction reference is sent as the client reference. If the LMS post is
     * idempotent on that field, say so in the open-questions list and the status check
     * before a re-post becomes an optimisation rather than a correctness control. Until
     * that is confirmed in writing, the status check stays mandatory.
     */
    @Override
    public JsonNode postTransaction(PostingRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fiid", props.getGlobal().getFiid());
        body.put("accountnumber", request.agreementNumber());
        body.put("tvstxnid", request.txnReference());
        body.put("amount", request.amount());
        body.put("paymentmode", request.paymentMode());
        body.put("paymentdate", request.paymentDate());
        body.put("vendorreference", request.vendorReference());

        return withFreshToken(() -> http.post(
                call(ApiName.POST_TXN, props.getGlobal().getLmsPostUrl())
                        .agreementNumber(request.agreementNumber())
                        .txnReference(request.txnReference())
                        .attemptNo(request.attemptNo())
                        .headers(headers())
                        .body(body)
                        .build())).json();
    }

    // =================================================================== plumbing

    /**
     * Every LMS call starts here: the upstream name, the API name for the audit row, the
     * URL, and the exception a borrower's request fails with. Written once.
     */
    private UpstreamCall.UpstreamCallBuilder call(String apiName, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("No URL configured for LMS API " + apiName);
        }
        return UpstreamCall.builder()
                .upstream(UPSTREAM)
                .apiName(apiName)
                .url(url)
                .failure(UpstreamException::lms);
    }

    /**
     * The headers every LMS business call carries.
     *
     * Resolved before the call object is built, so the token fetch happens outside the
     * bulkhead the business call will take a permit from - two sequential permits rather
     * than one nested inside another.
     */
    private Map<String, String> headers() {
        return headers(Map.of());
    }

    /** Same, plus whatever this call site needs. Additional headers win on a clash. */
    private Map<String, String> headers(Map<String, String> additional) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("tkn", token());
        headers.put("fiid", props.getGlobal().getFiid());
        headers.putAll(additional);
        return headers;
    }

    /**
     * Runs the call; on a rejected token, evicts and runs it once more.
     *
     * The retry rebuilds the call from scratch, which is the point - {@link #headers}
     * runs again and picks up the fresh bearer.
     */
    private UpstreamResponse withFreshToken(Supplier<UpstreamResponse> attempt) {
        try {
            return attempt.get();
        } catch (UpstreamStatusException e) {
            if (!e.isUnauthorized()) {
                throw e;
            }
            log.warn("LMS rejected the cached token (HTTP {}); evicting and retrying once",
                    e.getHttpStatus());
            tokenCache.evict(UPSTREAM);
            return attempt.get();
        }
    }

    /**
     * Bearer token for the LMS, cached cluster-wide in Redis.
     *
     * SEAM. The legacy getEXApiToken(fiid) implementation was not shared, so the request
     * below is the obvious reading of the call site: POST to the token URL with
     * {"fiid": n}, reading the token from {@code app.global.lms-token-json-field}.
     * Replace the body and the field name if yours differ; the cache, the early expiry,
     * the audit row and the 401 path above are independent of the request shape.
     */
    private String token() {
        return tokenCache.get(UPSTREAM, props.getGlobal().getLmsTokenTtlSeconds(), () -> {
            UpstreamResponse response = http.post(
                    call(ApiName.LMS_TOKEN, props.getGlobal().getLmsTokenUrl())
                            // No tkn header: this is the call that produces it.
                            .header("fiid", props.getGlobal().getFiid())
                            .body(Map.of("fiid", props.getGlobal().getFiid()))
                            // The response carries a credential. The request body is
                            // harmless; the response must never reach the audit table.
                            .recordResponseBody(false)
                            .classifier(UpstreamOutcome::fromHttpStatus)
                            .build());

            String token = response.text(props.getGlobal().getLmsTokenJsonField());
            if (token == null) {
                // The value is never logged. Only that the field was absent, which is
                // what you need to diagnose a contract change.
                log.error("LMS token response had no '{}' field",
                        props.getGlobal().getLmsTokenJsonField());
                throw UpstreamException.lms();
            }
            return token;
        });
    }

    /**
     * Pulls the authentication fields out of success[].
     *
     * The array can hold several accounts for one customer. For OTP dispatch exactly one
     * mobile number is needed, so distinct numbers are collected across all entries. If
     * more than one appears the lookup is rejected rather than silently using the first:
     * sending to success[0] when the numbers differ would deliver one borrower's code to
     * a different person's phone.
     *
     * If your LMS guarantees one number per customer this branch never fires. If it can
     * fire, you find out here and in the audit table rather than in a complaint.
     */
    private LoanDetails extract(JsonNode successArray, String requestedAgreement) {
        Set<String> mobiles = new LinkedHashSet<>();
        for (JsonNode entry : successArray) {
            String mobile = text(entry, "mobilenumber");
            if (mobile != null && !mobile.isBlank()) {
                mobiles.add(mobile.trim());
            }
        }

        if (mobiles.isEmpty()) {
            log.warn("LMS returned no mobile number for {}", Mask.agreement(requestedAgreement));
            throw new ApiException("AGREEMENT_NO_CONTACT",
                    "We do not have a mobile number on record for this account. "
                            + "Please contact customer support.", HttpStatus.CONFLICT);
        }
        if (mobiles.size() > 1) {
            log.warn("LMS returned {} distinct mobile numbers for {}; refusing to guess",
                    mobiles.size(), Mask.agreement(requestedAgreement));
            throw new ApiException("AGREEMENT_AMBIGUOUS",
                    "We could not verify those details. Please contact customer support.",
                    HttpStatus.CONFLICT);
        }

        JsonNode first = successArray.get(0);
        String mobile = mobiles.iterator().next();
        String accountNumber = text(first, "accountnumber");

        return LoanDetails.builder()
                .agreementNumber(accountNumber == null || accountNumber.isBlank()
                        ? requestedAgreement : accountNumber)
                .customerName(text(first, "customername"))
                .mobileNumber(mobile)
                .maskedMobile(Mask.mobile(mobile))
                .accountStatus(text(first, "accountStatus"))
                .agreementCount(successArray.size())
                .build();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
