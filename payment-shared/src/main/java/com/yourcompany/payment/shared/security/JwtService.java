package com.yourcompany.payment.shared.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.util.Mask;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Issues and verifies both JWTs the platform uses. JWS, HS256.
 *
 * <pre>
 *   aud=borrower   sub = sessionId    roles=[ROLE_BORROWER]            borrower secret
 *   aud=user       sub = username     roles=[ROLE_ADMIN|ROLE_VENDOR]   partner secret
 * </pre>
 *
 * Two secrets. Every service holds the borrower one because every service verifies
 * borrower tokens; if it leaks, the holder must not thereby be able to mint an
 * ROLE_ADMIN token. Startup fails if the two are equal.
 *
 * <h2>Verification, every request</h2>
 * Signature, algorithm, issuer, audience, expiry, issue time. The signature is one HMAC
 * and is never skipped — a token whose signature is not checked is a text field the
 * caller writes, and the role is in it.
 *
 * What is NOT done is any lookup. A user token is self-contained: the role comes from
 * the claim, so no verifying service needs the user table. The cost is that setting
 * ACTIVE='N' stops new tokens but does not kill one already issued — access ends when it
 * expires, 15 minutes later.
 *
 * <h2>The borrower token is bound, not merely signed</h2>
 * It fails if presented on a different session or against a different agreement. The
 * agreement travels as a keyed hash, not in the clear: a JWT payload is base64, not
 * encryption, so every claim is readable by anything that sees the token — devtools, a
 * proxy log, a crash report in a ticket. A loan account number does not belong there.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String AUD_BORROWER = "borrower";
    public static final String AUD_USER = "user";       // partner / admin machine accounts

    private static final JWSAlgorithm ALG = JWSAlgorithm.HS256;
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_AGREEMENT_HASH = "agrh";
    private static final int MIN_SECRET_BYTES = 32;      // HS256 needs a 256-bit key

    private final SharedProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Fail at startup, not on the first borrower. A short secret makes MACSigner throw at
     * issue time, which would be a 500 on verify-otp in production and nowhere else.
     */
    @PostConstruct
    public void validateSecrets() {
        requireStrong("app.security.token-hmac-secret", props.getSecurity().getTokenHmacSecret());
        requireStrong("app.security.partner-token-secret", props.getSecurity().getPartnerTokenSecret());

        if (props.getSecurity().getTokenHmacSecret()
                .equals(props.getSecurity().getPartnerTokenSecret())) {
            throw new IllegalStateException(
                    "app.security.partner-token-secret must differ from token-hmac-secret. "
                    + "Sharing them means a leak of the borrower secret — which every service "
                    + "holds — lets the holder mint partner tokens with any role.");
        }
    }

    private void requireStrong(String name, String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(name + " must be at least " + MIN_SECRET_BYTES
                    + " bytes for HS256. Generate one: openssl rand -base64 48");
        }
    }

    // ============================================================= borrower tokens

    /** Called by auth-service after OTP verification. Nowhere else. */
    public IssuedToken issueBorrowerToken(String sessionId, String agreementNumber) {
        long ttl = props.getSecurity().getTokenTtlSeconds();
        JWTClaimsSet claims = baseClaims(AUD_BORROWER, sessionId, ttl)
                .claim(CLAIM_ROLES, List.of(Role.BORROWER))
                .claim(CLAIM_AGREEMENT_HASH, agreementHash(agreementNumber))
                .build();
        return sign(claims, props.getSecurity().getTokenHmacSecret(), ttl);
    }

    /**
     * Everything that can be checked before the request body exists: signature,
     * algorithm, issuer, audience, expiry, and the binding to THIS session.
     *
     * Called by BorrowerAuthFilter. The returned claims carry the agreement hash, so the
     * second half of the binding can be checked once the decrypted body is available —
     * see {@link #requireAgreement}.
     *
     * Split in two because the agreement number lives in the AES-GCM encrypted body, and
     * a servlet filter runs before the decryption advice. Trying to do it all in one
     * place is what put header parsing in a controller.
     */
    public TokenClaims verifyBorrowerToken(String presented, String sessionId) {
        JWTClaimsSet claims = parseAndVerify(
                presented, props.getSecurity().getTokenHmacSecret(), AUD_BORROWER);

        if (!constantTimeEquals(claims.getSubject(), sessionId)) {
            log.warn("Borrower token presented on a different session: {}", Mask.sessionTail(sessionId));
            throw invalidToken();
        }

        return toTokenClaims(claims);
    }

    /**
     * The other half of the borrower binding: the agreement in the request must be the
     * one the token was issued for.
     *
     * MANDATORY on every borrower endpoint that names an agreement. A token verified by
     * the filter alone proves the caller owns the session, not that they may operate on
     * this loan — and the agreement number arrives in a body field the caller writes.
     * Skipping this is an IDOR: change one field, read another borrower's loan.
     */
    public void requireAgreement(TokenClaims claims, String agreementNumber) {
        if (!constantTimeEquals(claims.agreementHash(), agreementHash(agreementNumber))) {
            log.warn("Borrower token presented against a different agreement on session {}",
                    Mask.sessionTail(claims.subject()));
            throw invalidToken();
        }
    }

    // ============================================================== partner tokens

    /**
     * Called by auth-service once the username and password have been checked against
     * TVSCS_ONLINE_USER_PG. The role comes from that row — never from the request.
     */
    public IssuedToken issueUserToken(String username, String role) {
        if (!Role.isKnown(role)) {
            throw new IllegalArgumentException("Unknown role: " + role);
        }
        long ttl = props.getSecurity().getPartnerTokenTtlSeconds();
        JWTClaimsSet claims = baseClaims(AUD_USER, username, ttl)
                .claim(CLAIM_ROLES, List.of(role))
                .build();
        return sign(claims, props.getSecurity().getPartnerTokenSecret(), ttl);
    }

    /**
     * Signature, issuer, audience, expiry, role. Nothing else — no database read, no
     * Redis. The username is the subject, so the verifying service learns who is calling
     * from inside the signature rather than from a header the caller sets.
     *
     * The cost is that ACTIVE='N' does not take effect until the token expires. That is
     * the 15-minute window, and it is the trade stateless verification makes.
     */
    public TokenClaims verifyUserToken(String presented) {
        return toTokenClaims(parseAndVerify(
                presented, props.getSecurity().getPartnerTokenSecret(), AUD_USER));
    }

    // =================================================================== internals

    private JWTClaimsSet.Builder baseClaims(String audience, String subject, long ttlSeconds) {
        Instant now = Instant.now();
        byte[] raw = new byte[16];
        secureRandom.nextBytes(raw);

        return new JWTClaimsSet.Builder()
                .issuer(props.getSecurity().getJwtIssuer())
                .audience(audience)
                .subject(subject)
                .jwtID(HexFormat.of().formatHex(raw))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)));
    }

    private IssuedToken sign(JWTClaimsSet claims, String secret, long ttlSeconds) {
        try {
            // typ=JWT in the header. Explicit, so a verifier that checks it (ours does
            // not, but a partner's might) is not left guessing.
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(ALG).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return new IssuedToken(jwt.serialize(), claims.getJWTID(),
                    claims.getIssueTime().toInstant().toEpochMilli(), ttlSeconds);
        } catch (Exception e) {
            log.error("JWT signing failed: {}", e.getClass().getSimpleName());
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Parse, then verify, in that order, with the algorithm pinned before the signature
     * is checked.
     *
     * The pinning is the part that matters. The two classic JWT breaks both live in the
     * header: {@code alg: none}, where a library is talked into accepting an unsigned
     * token, and algorithm confusion, where a token arrives claiming HS256 and the
     * verifier obligingly treats an RSA public key as an HMAC secret. Neither works
     * against a verifier that decides the algorithm itself and reads the header only to
     * confirm it. Nimbus's MACVerifier would reject both anyway; this does not depend on
     * that being true in the next version.
     *
     * Every failure throws the same error. A caller must not be able to tell a bad
     * signature from an expired token from a wrong audience.
     */
    private JWTClaimsSet parseAndVerify(String presented, String secret, String expectedAudience) {
        if (presented == null || presented.isBlank()) {
            throw invalidToken();
        }

        try {
            SignedJWT jwt = SignedJWT.parse(presented.trim());

            if (!ALG.equals(jwt.getHeader().getAlgorithm())) {
                log.warn("JWT rejected: algorithm {} is not permitted", jwt.getHeader().getAlgorithm());
                throw invalidToken();
            }

            if (!jwt.verify(new MACVerifier(secret.getBytes(StandardCharsets.UTF_8)))) {
                throw invalidToken();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            long skew = props.getSecurity().getClockSkewToleranceSeconds();
            Instant now = Instant.now();

            if (!props.getSecurity().getJwtIssuer().equals(claims.getIssuer())) {
                throw invalidToken();
            }

            // A borrower token must never be accepted where a partner token is expected,
            // or the audience separation is decorative. Different secrets already make
            // this impossible; checking it too means the property survives a future
            // decision to share a key.
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                throw invalidToken();
            }

            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().plusSeconds(skew).isBefore(now)) {
                throw invalidToken();
            }

            // An iat in the future means a clock problem or a forged token. Either way
            // the age checks downstream cannot be trusted.
            if (claims.getIssueTime() == null
                    || claims.getIssueTime().toInstant().minusSeconds(skew).isAfter(now)) {
                throw invalidToken();
            }

            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw invalidToken();
            }

            return claims;

        } catch (ApiException e) {
            throw e;
        } catch (ParseException | com.nimbusds.jose.JOSEException e) {
            throw invalidToken();
        } catch (RuntimeException e) {
            log.warn("JWT verification failed: {}", e.getClass().getSimpleName());
            throw invalidToken();
        }
    }

    private TokenClaims toTokenClaims(JWTClaimsSet claims) {
        Set<String> roles = new LinkedHashSet<>();
        try {
            List<String> fromToken = claims.getStringListClaim(CLAIM_ROLES);
            if (fromToken != null) {
                // Drop anything not on the known list. A role that is not recognised
                // cannot authorise anything, and carrying it forward only makes a log
                // line misleading.
                fromToken.stream().filter(Role::isKnown).forEach(roles::add);
            }
        } catch (ParseException e) {
            throw invalidToken();
        }

        if (roles.isEmpty()) {
            throw invalidToken();
        }

        return new TokenClaims(claims.getSubject(), claims.getJWTID(),
                Set.copyOf(roles), claims.getExpirationTime().toInstant(),
                // Null on a user token; present on a borrower token so the agreement
                // binding can be completed after the body is decrypted.
                stringClaim(claims, CLAIM_AGREEMENT_HASH));
    }

    /**
     * Keyed hash of the agreement number, truncated to 128 bits.
     *
     * Keyed, not a plain SHA-256: agreement numbers are short and structured, so an
     * unkeyed digest is trivially reversed by enumeration. Truncation to 128 bits is
     * ample for a binding check and keeps the token small.
     */
    private String agreementHash(String agreementNumber) {
        if (agreementNumber == null || agreementNumber.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.getSecurity().getTokenHmacSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] full = mac.doFinal(agreementNumber.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(Arrays.copyOf(full, 16));
        } catch (Exception e) {
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            throw invalidToken();
        }
    }

    private ApiException invalidToken() {
        return new ApiException("TOKEN_INVALID",
                "Your authorisation has expired. Please verify again.", HttpStatus.UNAUTHORIZED);
    }

    public record IssuedToken(String token, String tokenId, long issuedAt, long expiresInSeconds) {}
}
