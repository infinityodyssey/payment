package com.yourcompany.payment.fetch.controller;

import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.fetch.service.FetchService;
import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.security.SessionContext;
import com.yourcompany.payment.shared.security.TokenClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower's fetch endpoint.
 *
 * Everything the method needs arrives as a parameter. By the time it runs the nonce has
 * been claimed, the session resolved from the cookie/header pair, the body decrypted, and
 * the JWT verified against that session — all in filters, all expressible as a matcher in
 * SecurityConfig. VerifiedCallerArgumentResolver supplies the two verified objects, so
 * there is no attribute lookup or null check here either.
 *
 * POST rather than GET, and not for REST tidiness: the body is AES-GCM encrypted by the
 * shared advice, so the agreement number must not sit in a URL, an access log or a
 * browser history. AntiReplayFilter also skips GET, so a GET here would silently lose
 * replay protection.
 */
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class FetchController {

    private final FetchService fetchService;

    @PostMapping("/fetch-details")
    public ResponseEntity<ApiResponse<FetchDtos.FetchResponse>> fetchDetails(
            @Valid @RequestBody FetchDtos.FetchRequest request,
            SessionContext session,
            TokenClaims claims) {
        return ResponseEntity.ok(ApiResponse.ok(
                fetchService.fetchForBorrower(request, session, claims)));
    }
}
