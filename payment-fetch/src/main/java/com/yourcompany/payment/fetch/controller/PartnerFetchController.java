package com.yourcompany.payment.fetch.controller;

import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.fetch.service.FetchService;
import com.yourcompany.payment.shared.crypto.SkipEncryption;
import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.security.TokenClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The partner's fetch endpoint. Same service, same response, different front door.
 *
 * {@code TokenClaims} arrives as a parameter, supplied by VerifiedCallerArgumentResolver
 * from what PartnerAuthFilter verified. No header reading, no attribute lookup, no null
 * check: if the filter did not run, the resolver fails the request rather than handing
 * this method a null.
 *
 * A SEPARATE PATH, not a branch inside one endpoint. The two callers authenticate in
 * entirely different ways — the browser brings an encrypted body, a session cookie paired
 * with a header and a nonce; a partner brings a plain body and a bearer JWT. Spring
 * Security selects a filter chain by path, so one path cannot apply both sets of filters:
 * it would have to decide from the request itself which model to use, and the only
 * available signals are chosen by the caller. Send a bearer token and no cookie, and a
 * branching endpoint skips the session checks.
 *
 * The role is matched by the chain (hasAnyAuthority(VENDOR, ADMIN)), so this path is
 * unreachable without it.
 *
 * {@code @SkipEncryption} because there is no ECDH handshake with a machine; TLS is the
 * transport security here.
 */
@SkipEncryption
@RestController
@RequestMapping("/api/v1/partner")
@RequiredArgsConstructor
public class PartnerFetchController {

    private final FetchService fetchService;

    @PostMapping("/fetch-details")
    public ResponseEntity<ApiResponse<FetchDtos.FetchResponse>> fetchDetails(
            @Valid @RequestBody FetchDtos.FetchRequest request, TokenClaims claims) {
        return ResponseEntity.ok(ApiResponse.ok(fetchService.fetchForPartner(request, claims)));
    }
}
