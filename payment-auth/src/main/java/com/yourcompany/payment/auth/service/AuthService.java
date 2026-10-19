package com.yourcompany.payment.auth.service;

import com.yourcompany.payment.auth.dto.AuthDtos;
import com.yourcompany.payment.auth.session.SessionWriteService;
import com.yourcompany.payment.shared.client.LmsClient;
import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.crypto.EcdhKeyService;
import com.yourcompany.payment.shared.dto.LoanDetails;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.session.LmsSnapshot;
import com.yourcompany.payment.shared.session.LmsSnapshotStore;
import com.yourcompany.payment.shared.session.PaymentSession;
import com.yourcompany.payment.shared.session.SessionState;
import com.yourcompany.payment.shared.util.AuditEventType;
import com.yourcompany.payment.shared.util.Mask;
import com.yourcompany.payment.shared.web.AuditService;
import com.yourcompany.payment.shared.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.util.Arrays;
import java.time.Instant;
import java.util.Optional;

/**
 * Sequences the authentication steps and owns the state transitions.
 * All security decisions live in the collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String CLAIM_TOKEN_ISSUE = "token-issue";

    private final EcdhKeyService ecdhKeyService;
    private final SessionWriteService sessions;
    private final LmsClient lmsClient;
    private final LmsSnapshotStore lmsSnapshots;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final ClientContext clientContext;
    private final SharedProperties props;

    /** Step 0: ephemeral key agreement. */
    public AuthDtos.HandshakeResponse handshake(AuthDtos.HandshakeRequest request, HttpServletRequest http) {
        var clientPublicKey = ecdhKeyService.parseAndValidatePublicKey(request.getClientPublicKeyHex());
        KeyPair serverKeyPair = ecdhKeyService.generateEphemeralKeyPair();

        // Minted first because the id is the HKDF salt: the derived key is unique to this
        // session even if either side's RNG repeats.
        String sessionId = sessions.mintSessionId();
        byte[] sessionKey = ecdhKeyService.deriveSessionKey(
                serverKeyPair.getPrivate(), clientPublicKey, sessionId);

        try {
            sessions.create(sessionId, sessionKey, clientContext.fingerprint(http));
        } finally {
            // The server private key is now unreferenced and the key buffer wiped.
            // Neither is written to Redis or Oracle, so a database compromise cannot
            // decrypt traffic captured today.
            Arrays.fill(sessionKey, (byte) 0);
        }

        auditService.record(AuditEventType.HANDSHAKE, AuditEventType.SUCCESS, null,
                sessionId, null, http);

        return AuthDtos.HandshakeResponse.builder()
                .sessionId(sessionId)
                .serverPublicKeyHex(ecdhKeyService.exportPublicKeyHex(serverKeyPair.getPublic()))
                .expiresInSeconds(props.getSecurity().getSessionTtlSeconds())
                .build();
    }

    /** Step 1: agreement lookup and OTP dispatch. */
    public AuthDtos.VerifyAgreementResponse verifyAgreement(String sessionId,
                                                            AuthDtos.VerifyAgreementRequest request,
                                                            HttpServletRequest http) {
        PaymentSession session = sessions.get(sessionId);
        sessions.requireState(session, SessionState.HANDSHAKE_COMPLETE, SessionState.OTP_SENT);
        assertFingerprint(session, http);

        String agreementNumber = request.getAgreementNumber().toUpperCase();

        // auth-service calling the LMS directly is the one exception to fetch-service
        // owning LMS reads, and it is deliberate: the mobile number for OTP dispatch
        // comes from here, and routing it through fetch-service would put a network hop
        // inside the OTP flow to save nothing. Do not "fix" this later.
        //
        // The response is captured as a session snapshot so fetch-service can serve the
        // customer-details screen without a second call.
        Optional<LmsClient.AgreementLookup> found = lmsClient.lookupWithPayload(agreementNumber);

        if (found.isEmpty()) {
            auditService.record(AuditEventType.AGREEMENT_LOOKUP, AuditEventType.FAILURE,
                    "AGREEMENT_NOT_FOUND", sessionId, agreementNumber, http);
            log.info("Agreement lookup miss for {}", Mask.agreement(agreementNumber));
            // The LMS returns the same shape for "no such agreement" and "lookup failed",
            // so this response cannot confirm whether the number is real.
            throw new ApiException("AGREEMENT_LOOKUP_FAILED",
                    "We could not verify those details. Please check and try again.",
                    HttpStatus.UNAUTHORIZED);
        }

        LoanDetails details = found.get().details();

        // Rebinding an established session to a different loan is not a legitimate flow;
        // it is how one verified session would be reused across accounts.
        if (session.getAgreementNumber() != null
                && !session.getAgreementNumber().equals(details.getAgreementNumber())) {
            sessions.invalidate(sessionId);
            throw new ApiException("SESSION_REBIND_REJECTED",
                    "Please reload the page and start again.", HttpStatus.CONFLICT);
        }

        otpService.issue(sessionId, details.getAgreementNumber(), details.getMobileNumber());

        session.setAgreementNumber(details.getAgreementNumber());
        session.setCustomerName(details.getCustomerName());
        session.setMaskedMobile(details.getMaskedMobile());
        session.setState(SessionState.OTP_SENT);
        sessions.save(session);

        // Scoped to this session only. Two sessions for the same borrower hold separate
        // snapshots and may legitimately disagree — each shows what was true when it
        // started.
        lmsSnapshots.put(sessionId, LmsSnapshot.builder()
                .details(details)
                .rawAgreementJson(found.get().rawAgreementJson())
                .capturedAt(Instant.now())
                .build());

        auditService.record(AuditEventType.OTP_ISSUED, AuditEventType.SUCCESS, null,
                sessionId, details.getAgreementNumber(), http);

        return response(details.getMaskedMobile());
    }

    /**
     * Step 1b: resend, under the same cooldown and budget as the first send.
     *
     * Makes NO LMS call. The mobile number is already in the session snapshot, and a
     * resend should reach the same number the first code went to. This also extends the
     * snapshot's TTL alongside the session.
     */
    public AuthDtos.VerifyAgreementResponse resendOtp(String sessionId, HttpServletRequest http) {
        PaymentSession session = sessions.get(sessionId);
        sessions.requireState(session, SessionState.OTP_SENT);
        assertFingerprint(session, http);

        LoanDetails details = lmsSnapshots.get(sessionId)
                .map(LmsSnapshot::getDetails)
                .orElseThrow(() -> new ApiException("SESSION_INVALID",
                        "Your session has ended. Please start again.", HttpStatus.UNAUTHORIZED));

        otpService.issue(sessionId, details.getAgreementNumber(), details.getMobileNumber());
        lmsSnapshots.touch(sessionId);

        auditService.record(AuditEventType.OTP_RESEND, AuditEventType.SUCCESS, null,
                sessionId, session.getAgreementNumber(), http);

        return response(details.getMaskedMobile());
    }

    /** Step 2: verification and payment-token issuance. */
    public AuthDtos.VerifyOtpResponse verifyOtp(String sessionId,
                                                AuthDtos.VerifyOtpRequest request,
                                                HttpServletRequest http) {
        PaymentSession session = sessions.get(sessionId);
        sessions.requireState(session, SessionState.OTP_SENT);
        assertFingerprint(session, http);

        try {
            otpService.verify(sessionId, request.getOtp());
        } catch (ApiException e) {
            // Covers every rejection path, including OTP_ALREADY_USED — the branch taken
            // when two concurrent requests carry the same correct code and this one lost
            // the DEL race. That branch is rare and is exactly the one worth seeing in
            // the audit table if something odd is happening.
            auditService.record(AuditEventType.OTP_VERIFY, AuditEventType.FAILURE, e.getErrorCode(),
                    sessionId, session.getAgreementNumber(), http);
            throw e;
        }

        // One token per session. The OTP DEL already elects a single winner and the
        // OTP_SENT state check blocks a second verification, so in normal operation this
        // never fires. It is here because token issuance is where two winners would be
        // expensive.
        if (!sessions.claimOnce(sessionId, CLAIM_TOKEN_ISSUE)) {
            auditService.record(AuditEventType.TOKEN_ISSUED, AuditEventType.FAILURE,
                    "TOKEN_ALREADY_ISSUED", sessionId, session.getAgreementNumber(), http);
            throw new ApiException("TOKEN_ALREADY_ISSUED",
                    "This code has already been verified. Please continue in the original tab.",
                    HttpStatus.CONFLICT);
        }

        JwtService.IssuedToken issued;
        try {
            issued = jwtService.issueBorrowerToken(sessionId, session.getAgreementNumber());

            session.setState(SessionState.OTP_VERIFIED);
            session.setTokenId(issued.tokenId());
            session.setTokenIssuedAt(issued.issuedAt());
            sessions.save(session);
        } catch (RuntimeException e) {
            // The session moved under us, so the signed token is bound to a version never
            // stored. Release the claim so a clean retry is possible rather than handing
            // the borrower a credential that fails on first use.
            sessions.releaseClaim(sessionId, CLAIM_TOKEN_ISSUE);
            throw e;
        }

        auditService.record(AuditEventType.OTP_VERIFY, AuditEventType.SUCCESS, null,
                sessionId, session.getAgreementNumber(), http);
        auditService.record(AuditEventType.TOKEN_ISSUED, AuditEventType.SUCCESS, null,
                sessionId, session.getAgreementNumber(), http);

        // The token goes to the client inside the encrypted envelope and is never logged.
        return AuthDtos.VerifyOtpResponse.builder()
                .customerName(session.getCustomerName())
                .agreementNumber(session.getAgreementNumber())
                .paymentAuthToken(issued.token())
                .tokenType("Bearer")
                .expiresInSeconds(issued.expiresInSeconds())
                .build();
    }

    /**
     * Deliberately weak: User-Agent and Accept-Language, no IP, because mobile users
     * change IP mid-journey. It breaks trivial cookie replay from another tool; it will
     * not stop an attacker who already holds the cookie.
     */
    private void assertFingerprint(PaymentSession session, HttpServletRequest http) {
        String current = clientContext.fingerprint(http);
        if (session.getClientFingerprint() != null
                && !session.getClientFingerprint().equals(current)) {
            log.warn("Client fingerprint changed for session {}", Mask.sessionTail(session.getSessionId()));
            // invalidate() drops the snapshot too — all session keys go together.
            sessions.invalidate(session.getSessionId());
            throw new ApiException("SESSION_REQUIRED",
                    "Your session has ended. Please reload the page.", HttpStatus.UNAUTHORIZED);
        }
    }

    private AuthDtos.VerifyAgreementResponse response(String maskedMobile) {
        return AuthDtos.VerifyAgreementResponse.builder()
                .maskedMobile(maskedMobile)
                .otpExpiresInSeconds(otpTtl())
                .resendAvailableInSeconds(resendCooldown())
                .build();
    }

    // Kept as methods so the controller layer never reaches into AuthProperties.
    private long otpTtl() {
        return otpService.ttlSeconds();
    }

    private long resendCooldown() {
        return otpService.resendCooldownSeconds();
    }
}
