package com.yourcompany.payment.fetch.controller;

import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.fetch.service.FetchService;
import com.yourcompany.payment.shared.crypto.SkipEncryption;
import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.security.PartnerAuthFilter;
import com.yourcompany.payment.shared.security.TokenClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The partner's fetch endpoint. Same service, same response, different front door.
 *
 * A SEPARATE PATH, not a branch inside one endpoint, and this is the design decision
 * worth defending. The two callers authenticate in entirely different ways: the browser
 * brings an encrypted body, a session cookie paired with a header, a nonce and a
 * session-bound token; a partner brings a plain body and a bearer JWT. Spring Security
 * selects a filter chain by path, so one path cannot apply both sets of filters — it
 * would have to decide from the request itself which model to use, and the only
 * available signals (a cookie here, a header there) are chosen by the caller.
 *
 * That is a confused-deputy bug waiting to be written: send a bearer token and no
 * cookie, and a branching endpoint skips the session checks. Two paths make the choice
 * structural. The shared filters already assume this split — both SessionContextFilter
 * and AntiReplayFilter exclude /api/v1/partner/ by prefix.
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
            @Valid @RequestBody FetchDtos.FetchRequest request, HttpServletRequest http) {

        // Put there by PartnerAuthFilter after verifying the signature. Reading claims
        // from the attribute rather than re-parsing the header means there is no way for
        // a controller to see an unverified token.
        TokenClaims claims = (TokenClaims) http.getAttribute(PartnerAuthFilter.PARTNER_CLAIMS);
        if (claims == null) {
            throw new ApiException("PARTNER_TOKEN_MISSING", "Authentication required.",
                    HttpStatus.UNAUTHORIZED);
        }

        return ResponseEntity.ok(ApiResponse.ok(fetchService.fetchForPartner(request, claims)));
    }
}
