package com.yourcompany.payment.auth.controller;

import com.yourcompany.payment.auth.dto.AuthDtos;
import com.yourcompany.payment.auth.service.AuthService;
import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.crypto.SkipEncryption;
import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.security.SecurityHeaders;
import com.yourcompany.payment.shared.security.SessionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four authentication endpoints. No business logic: the controller adapts HTTP to
 * AuthService and owns the session cookie, nothing else.
 *
 * Note the absence of any rate-limiting call. Per-IP and per-route limits belong to the
 * API gateway. The limits that cannot live there — OTP attempts and the per-agreement
 * send budget — are enforced in OtpService, because they key on a value inside an
 * encrypted body the gateway cannot read.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SharedProperties props;

    /** The only unauthenticated endpoint and the only one exempt from encryption. */
    @PostMapping("/handshake")
    @SkipEncryption
    public ResponseEntity<ApiResponse<AuthDtos.HandshakeResponse>> handshake(
            @Valid @RequestBody AuthDtos.HandshakeRequest request,
            HttpServletRequest http,
            HttpServletResponse httpResponse) {

        AuthDtos.HandshakeResponse response = authService.handshake(request, http);

        // The __Host- prefix is accepted by browsers only with Secure set, Path "/" and
        // no Domain attribute, which host-locks the cookie: a compromised or
        // attacker-controlled sibling subdomain cannot overwrite it.
        ResponseCookie cookie = ResponseCookie.from(SecurityHeaders.SESSION_COOKIE, response.getSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(props.getSecurity().getSessionTtlSeconds())
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/verify-agreement")
    public ResponseEntity<ApiResponse<AuthDtos.VerifyAgreementResponse>> verifyAgreement(
            @Valid @RequestBody AuthDtos.VerifyAgreementRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.verifyAgreement(sessionId(http), request, http)));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<AuthDtos.VerifyAgreementResponse>> resendOtp(HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(authService.resendOtp(sessionId(http), http)));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthDtos.VerifyOtpResponse>> verifyOtp(
            @Valid @RequestBody AuthDtos.VerifyOtpRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.verifyOtp(sessionId(http), request, http)));
    }

    /** Set by SessionContextFilter after the cookie/header double-submit check. */
    private String sessionId(HttpServletRequest http) {
        SessionContext ctx = (SessionContext) http.getAttribute(SessionContext.ATTRIBUTE);
        return ctx == null ? null : ctx.sessionId();
    }
}
