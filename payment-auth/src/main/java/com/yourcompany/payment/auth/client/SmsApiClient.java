package com.yourcompany.payment.auth.client;

import com.yourcompany.payment.auth.config.AuthProperties;
import com.yourcompany.payment.shared.client.ApiName;
import com.yourcompany.payment.shared.client.UpstreamCall;
import com.yourcompany.payment.shared.client.UpstreamHttpClient;
import com.yourcompany.payment.shared.client.UpstreamOutcome;
import com.yourcompany.payment.shared.client.UpstreamResponse;
import com.yourcompany.payment.shared.client.UpstreamTokenCache;
import com.yourcompany.payment.shared.exception.UpstreamException;
import com.yourcompany.payment.shared.exception.UpstreamStatusException;
import com.yourcompany.payment.shared.util.Mask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The SMS gateway: token and dispatch, in one class, on the shared transport.
 *
 * Same shape as {@link com.yourcompany.payment.shared.client.LmsApiClient} and for the
 * same reasons - one header assembly point, one token path, no HTTP code of its own. The
 * only thing specific to this upstream is that it bounds concurrency and failure
 * separately: {@code resilience4j.*.instances.sms} and the {@code smsRestClient} bean,
 * selected by the upstream name below. A slow LMS must not consume the capacity that
 * sends one-time codes.
 *
 * <h2>Known risk, carried from the existing integration</h2>
 * The gateway takes the message as a GET query parameter, so the code and the mobile
 * number appear in the request line and therefore in the access logs of every hop -
 * egress proxy, their load balancer, their web server. Every other part of this platform
 * is built so the code never reaches a log; this one hop defeats that and cannot be
 * fixed from our side. The fix is a POST with the message in the body. Until the provider
 * supports it, this needs a named risk owner and written confirmation of their
 * access-log retention.
 *
 * <h2>Two defects in the legacy implementation, fixed here</h2>
 * {@code appURLsms.replace(" ", "%20")} encoded spaces and nothing else, so &, =, + and
 * # in the message passed through raw; UriComponentsBuilder encodes properly. And the
 * gateway's response was logged verbatim - it is now redacted before it is persisted.
 *
 * The message wording is the DLT-registered template and must not be edited without
 * re-registering it, or the operator will drop the message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsApiClient implements OtpSender {

    /** Names the RestClient bean, the bulkhead and the circuit breaker. All three. */
    private static final String UPSTREAM = "sms";

    private final UpstreamHttpClient http;
    private final UpstreamTokenCache tokenCache;
    private final AuthProperties props;

    @Override
    public void send(String mobileNumber, String otpCode, String agreementNumber) {
        AuthProperties.Sms sms = props.getSms();
        String url = UriComponentsBuilder.fromUriString(sms.getSendUrl())
                .queryParam("Lang", sms.getLang())
                .queryParam("Phone", mobileNumber)
                .queryParam("Purpose", sms.getPurpose())
                .queryParam("Template", sms.getTemplate())
                .queryParam("EmpId", sms.getEmpId())
                .queryParam("Msg", sms.getMessageTemplate().replace("{otp}", otpCode))
                .build()
                .encode()
                .toUriString();

        // The endpoint and the response both carry the code and the number. The audit row
        // must never become the place a one-time secret is retained, so the redactor goes
        // on the call and the transport applies it before anything is written.
        UnaryOperator<String> redactor = value -> value == null ? null
                : value.replace(otpCode, "[OTP]").replace(mobileNumber, "[MOBILE]");

        UpstreamResponse response = withFreshToken(() -> http.get(
                call(ApiName.SMS_SEND, url)
                        .agreementNumber(agreementNumber)
                        .headers(headers())
                        .redactor(redactor)
                        .classifier(r -> "SUCCESS".equalsIgnoreCase(r.text("status"))
                                ? UpstreamOutcome.success(r.text("status"), null)
                                : UpstreamOutcome.failure(r.text("status"), null,
                                        "gateway did not accept"))
                        .build()));

        if (!"SUCCESS".equalsIgnoreCase(response.text("status"))) {
            // A dispatch failure must reach the borrower. The legacy code swallowed it
            // and left them waiting for a message that was never sent.
            log.error("SMS gateway rejected dispatch for {}", Mask.agreement(agreementNumber));
            throw UpstreamException.sms();
        }

        log.info("OTP dispatched to {} for {}",
                Mask.mobile(mobileNumber), Mask.agreement(agreementNumber));
    }

    // =================================================================== plumbing

    private UpstreamCall.UpstreamCallBuilder call(String apiName, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("No URL configured for SMS API " + apiName);
        }
        return UpstreamCall.builder()
                .upstream(UPSTREAM)
                .apiName(apiName)
                .url(url)
                .failure(UpstreamException::sms);
    }

    /** The single header assembly point for this upstream. Extras merge on top. */
    private Map<String, String> headers() {
        return headers(Map.of());
    }

    private Map<String, String> headers(Map<String, String> additional) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token());
        headers.putAll(additional);
        return headers;
    }

    private UpstreamResponse withFreshToken(Supplier<UpstreamResponse> attempt) {
        try {
            return attempt.get();
        } catch (UpstreamStatusException e) {
            if (!e.isUnauthorized()) {
                throw e;
            }
            log.warn("SMS gateway rejected the cached token (HTTP {}); evicting and retrying once",
                    e.getHttpStatus());
            tokenCache.evict(UPSTREAM);
            return attempt.get();
        }
    }

    /**
     * Bearer token for the gateway, cached cluster-wide in Redis.
     *
     * SEAM. The legacy getSmsAccessToken() implementation was not shared, so this is the
     * obvious reading of the call site: a bare POST to the token URL, reading the token
     * from {@code auth.sms.token-json-field}. Replace the request and the field name if
     * yours differ; nothing around it changes.
     */
    private String token() {
        return tokenCache.get(UPSTREAM, props.getSms().getTokenTtlSeconds(), () -> {
            UpstreamResponse response = http.post(
                    call(ApiName.SMS_TOKEN, props.getSms().getTokenUrl())
                            .recordResponseBody(false)
                            .classifier(UpstreamOutcome::fromHttpStatus)
                            .build());

            String token = response.text(props.getSms().getTokenJsonField());
            if (token == null) {
                log.error("SMS token response had no '{}' field", props.getSms().getTokenJsonField());
                throw UpstreamException.sms();
            }
            return token;
        });
    }
}
