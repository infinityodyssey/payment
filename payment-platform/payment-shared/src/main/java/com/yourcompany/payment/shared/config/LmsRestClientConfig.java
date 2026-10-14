package com.yourcompany.payment.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client for the LMS, defined once for every service that calls it.
 *
 * Four of the five services talk to the LMS, and before the clients were consolidated
 * each had to declare this bean itself - four chances for one of them to drift to a
 * different timeout. {@link com.yourcompany.payment.shared.client.UpstreamHttpClient}
 * resolves it by name, so the bean name is part of the contract: {@code lmsRestClient},
 * matching the upstream name {@code lms} and therefore the bulkhead and circuit breaker
 * instances of the same name.
 *
 * The read timeout matches the LMS's stated 30 seconds, which has consequences. The
 * Angular client needs a longer timeout than that or it shows a failure while the
 * backend is still waiting, and the circuit breaker is configured on slow-call rate
 * rather than failure rate alone, because 30 seconds of held connections exhausts
 * capacity long before enough calls outright fail to trip a failure-rate breaker.
 *
 * The connect timeout stays short: a slow response is the upstream working, an
 * unreachable host is not, and there is no reason to wait 30 seconds to learn that a
 * port is closed. Redirects are never followed - a redirect from a payment upstream is
 * either a misconfiguration or someone else's login page.
 */
@Configuration
@RequiredArgsConstructor
public class LmsRestClientConfig {

    private final SharedProperties shared;

    @Bean("lmsRestClient")
    public RestClient lmsRestClient() {
        return restClient(shared.getGlobal().getLmsTimeoutSeconds());
    }

    /** Shared builder so every upstream client is created the same way. */
    public static RestClient restClient(int readTimeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        return RestClient.builder().requestFactory(factory).build();
    }
}
