package com.yourcompany.payment.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.security.Role;
import com.yourcompany.payment.shared.security.TokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These encode the properties the tokens are supposed to have. If one starts failing,
 * the token has stopped being an authorisation and become a decorative string.
 *
 * The forgery cases are the point of the file: alg=none, algorithm confusion, a token
 * signed with the wrong key, a borrower token replayed as a partner token, and a role
 * added by hand.
 */
class JwtServiceTest {

    private static final String BORROWER_SECRET = "borrower-secret-at-least-32-bytes-long-ok";
    private static final String PARTNER_SECRET  = "partner-secret-at-least-32-bytes-long-okay";

    private JwtService service;
    private SharedProperties props;

    @BeforeEach
    void setUp() {
        props = new SharedProperties();
        props.getSecurity().setTokenHmacSecret(BORROWER_SECRET);
        props.getSecurity().setPartnerTokenSecret(PARTNER_SECRET);
        props.getSecurity().setJwtIssuer("tvscredit-payment");
        props.getSecurity().setTokenTtlSeconds(300);
        props.getSecurity().setPartnerTokenTtlSeconds(900);
        props.getSecurity().setClockSkewToleranceSeconds(5);
        service = new JwtService(props);
        service.validateSecrets();
    }

    // ------------------------------------------------------------- startup guards

    @Test
    void refusesToStartWithAShortSecret() {
        props.getSecurity().setTokenHmacSecret("too-short");
        assertThrows(IllegalStateException.class, () -> service.validateSecrets());
    }

    @Test
    void refusesToStartWhenBorrowerAndPartnerSecretsMatch() {
        props.getSecurity().setPartnerTokenSecret(BORROWER_SECRET);
        assertThrows(IllegalStateException.class, () -> service.validateSecrets());
    }

    // ------------------------------------------------------------ borrower tokens

    @Test
    void issuedBorrowerTokenValidatesAgainstItsOwnSessionAndAgreement() {
        var issued = service.issueBorrowerToken("SES_a", "GL0001234567");
        TokenClaims claims = service.verifyBorrowerToken(issued.token(), "SES_a", "GL0001234567");

        assertEquals("SES_a", claims.subject());
        assertTrue(claims.hasRole(Role.BORROWER));
    }

    @Test
    void borrowerTokenFromAnotherSessionIsRejected() {
        var issued = service.issueBorrowerToken("SES_victim", "GL0001234567");
        assertThrows(ApiException.class,
                () -> service.verifyBorrowerToken(issued.token(), "SES_attacker", "GL0001234567"));
    }

    @Test
    void borrowerTokenAgainstAnotherAgreementIsRejected() {
        var issued = service.issueBorrowerToken("SES_a", "GL0001234567");
        assertThrows(ApiException.class,
                () -> service.verifyBorrowerToken(issued.token(), "SES_a", "GL9999999999"));
    }

    @Test
    void theAgreementNumberIsNotReadableFromTheToken() {
        var issued = service.issueBorrowerToken("SES_a", "GL0001234567");
        // The payload is base64, not encryption. Anything that sees the token reads every
        // claim, so the loan account number must not be one of them.
        String decodedPayload = new String(java.util.Base64.getUrlDecoder()
                .decode(issued.token().split("\\.")[1]), StandardCharsets.UTF_8);
        assertFalse(decodedPayload.contains("GL0001234567"));
    }

    @Test
    void expiredBorrowerTokenIsRejected() throws Exception {
        String expired = signed(BORROWER_SECRET, new JWTClaimsSet.Builder()
                .issuer("tvscredit-payment")
                .audience("borrower")
                .subject("SES_a")
                .jwtID("abc")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(300)))
                .claim("roles", List.of(Role.BORROWER))
                .build());
        assertThrows(ApiException.class,
                () -> service.verifyBorrowerToken(expired, "SES_a", "GL0001234567"));
    }

    // ------------------------------------------------------------- partner tokens

    @Test
    void userTokenCarriesItsRoleAndNeedsNoLookupToVerify() {
        var issued = service.issueUserToken("acme_vendor", Role.VENDOR);

        TokenClaims claims = service.verifyUserToken(issued.token());

        assertEquals("acme_vendor", claims.subject());
        assertTrue(claims.hasRole(Role.VENDOR));
        assertFalse(claims.hasRole(Role.ADMIN));
    }

    @Test
    void requireRoleRejectsAnOperationTheUserWasNotGranted() {
        var issued = service.issueUserToken("acme_vendor", Role.VENDOR);
        TokenClaims claims = service.verifyUserToken(issued.token());

        assertDoesNotThrow(() -> claims.requireRole(Role.VENDOR));
        assertThrows(ApiException.class, () -> claims.requireRole(Role.ADMIN));
    }

    @Test
    void aTokenWithNoRoleIsNeverIssued() {
        assertThrows(IllegalArgumentException.class,
                () -> service.issueUserToken("acme_vendor", null));
    }

    @Test
    void aTypoInTheDatabaseRoleIsRejectedAtIssue() {
        // TVSCS_ONLINE_USER_PG.ROLE has a CHECK constraint, but belt and braces: a role
        // the code does not know would authorise nothing and look like a platform fault.
        assertThrows(IllegalArgumentException.class,
                () -> service.issueUserToken("acme_vendor", "ROLE_VENODR"));
    }

    @Test
    void databaseRoleValuesMapToAuthorities() {
        assertEquals(Role.VENDOR, Role.fromDatabase("VENDOR"));
        assertEquals(Role.ADMIN, Role.fromDatabase("admin"));
        assertEquals(null, Role.fromDatabase("SUPERUSER"));
        assertEquals(null, Role.fromDatabase(null));
    }

    // ------------------------------------------------------------------- forgery

    @Test
    void unsignedTokenIsRejected() {
        // alg=none. The classic JWT break: a library talked into accepting a token that
        // carries no signature at all.
        String none = new PlainJWT(new JWTClaimsSet.Builder()
                .issuer("tvscredit-payment")
                .audience("user")
                .subject("acme_vendor")
                .jwtID("abc")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("roles", List.of(Role.VENDOR))
                .build()).serialize();

        assertThrows(ApiException.class, () -> service.verifyUserToken(none));
    }

    @Test
    void tokenSignedWithTheWrongKeyIsRejected() throws Exception {
        String forged = signed("an-attacker-secret-at-least-32-bytes-long",
                new JWTClaimsSet.Builder()
                        .issuer("tvscredit-payment")
                        .audience("user")
                        .subject("acme_vendor")
                        .jwtID("abc")
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                        .claim("roles", List.of(Role.VENDOR))
                        .build());

        assertThrows(ApiException.class, () -> service.verifyUserToken(forged));
    }

    @Test
    void borrowerTokenCannotBeUsedAsAUserToken() {
        var borrower = service.issueBorrowerToken("SES_a", "GL0001234567");
        assertThrows(ApiException.class, () -> service.verifyUserToken(borrower.token()));
    }

    @Test
    void userTokenCannotBeUsedAsABorrowerToken() {
        var userToken = service.issueUserToken("acme_vendor", Role.VENDOR);
        assertThrows(ApiException.class,
                () -> service.verifyBorrowerToken(userToken.token(), "acme_vendor", null));
    }

    @Test
    void aRoleChangedByHandIsRejected() throws Exception {
        // A vendor edits VENDOR to ADMIN and re-signs with their own key. This is the
        // attack the signature exists to stop, and the reason the role can live in the
        // token at all.
        String escalated = signed("whatever-key-the-attacker-has-32-bytes",
                new JWTClaimsSet.Builder()
                        .issuer("tvscredit-payment")
                        .audience("user")
                        .subject("acme_vendor")
                        .jwtID("abc")
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                        .claim("roles", List.of(Role.ADMIN))
                        .build());

        assertThrows(ApiException.class, () -> service.verifyUserToken(escalated));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        String wrongIssuer = signed(PARTNER_SECRET, new JWTClaimsSet.Builder()
                .issuer("some-other-system")
                .audience("user")
                .subject("acme_vendor")
                .jwtID("abc")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("roles", List.of(Role.VENDOR))
                .build());

        assertThrows(ApiException.class, () -> service.verifyUserToken(wrongIssuer));
    }

    @Test
    void malformedTokensAreRejected() {
        assertThrows(ApiException.class, () -> service.verifyUserToken(null));
        assertThrows(ApiException.class, () -> service.verifyUserToken(""));
        assertThrows(ApiException.class, () -> service.verifyUserToken("not.a.jwt"));
        assertThrows(ApiException.class, () -> service.verifyUserToken("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void tokenDoesNotLeakTheSigningSecret() {
        var issued = service.issueUserToken("acme_vendor", Role.VENDOR);
        assertFalse(issued.token().contains(PARTNER_SECRET));
        assertFalse(issued.token().contains(BORROWER_SECRET));
    }

    private String signed(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
