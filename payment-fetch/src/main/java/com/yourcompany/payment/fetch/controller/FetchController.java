package com.yourcompany.payment.fetch.controller;

import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.fetch.service.FetchService;
import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.exception.SessionException;
import com.yourcompany.payment.shared.security.SecurityHeaders;
import com.yourcompany.payment.shared.security.SessionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower's fetch endpoint.
 *
 * POST, not GET, and that is not REST pedantry: the request body is AES-GCM encrypted by
 * the shared advice, and the agreement number must not appear in a URL, an access log or
 * a browser history. The anti-replay filter also skips GET, so a GET here would lose
 * replay protection.
 *
 * The controller adapts transport to a service call and does nothing else. Decryption,
 * the session cookie/header pair, the nonce and the TTL slide all happen before this
 * method runs.
 */
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class FetchController {

    private final FetchService fetchService;

    @PostMapping("/fetch-details")
    public ResponseEntity<ApiResponse<FetchDtos.FetchResponse>> fetchDetails(
            @Valid @RequestBody FetchDtos.FetchRequest request,
            @RequestHeader(SecurityHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest http) {

        SessionContext session = (SessionContext) http.getAttribute(SessionContext.ATTRIBUTE);
        if (session == null) {
            // The filter chain should have made this impossible. If it ever happens the
            // filter is misconfigured, and failing closed is the only safe answer.
            throw SessionException.required();
        }

        return ResponseEntity.ok(ApiResponse.ok(
                fetchService.fetchForBorrower(request, session, bearer(authorization))));
    }

    private String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw SessionException.required();
        }
        return header.substring("Bearer ".length());
    }
}
