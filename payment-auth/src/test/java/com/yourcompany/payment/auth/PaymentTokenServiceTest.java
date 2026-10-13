package com.yourcompany.payment.auth;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.security.PaymentTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * These encode the properties the token is supposed to have. If one starts failing, the
 * token has stopped being an authorisation and become a decorative string.
 *
 * The cross-session and cross-agreement cases are the important ones: they are what let
 * fetch-service and payment-service enforce binding without reading session state.
 */
class PaymentTokenServiceTest {

    private static final String SESSION = "SES_" + "a".repeat(64);
    private static final String OTHER_SESSION = "SES_" + "b".repeat(64);
    private static final String AGREEMENT = "GL0001234567";

    private PaymentTokenService service;
    private SharedProperties props;

    @BeforeEach
    void setUp() {
        props = new SharedProperties();
        props.getSecurity().setTokenHmacSecret("unit-test-secret-value-long-enough-32-bytes");
        props.getSecurity().setTokenTtlSeconds(300);
        props.getSecurity().setClockSkewToleranceSeconds(5);
        service = new PaymentTokenService(props);
    }

    @Test
    void tokenVerifiesAgainstItsOwnSessionAndAgreement() {
        var issued = service.issue(SESSION, AGREEMENT);
        assertDoesNotThrow(() -> service.verify(issued.token(), SESSION, AGREEMENT));
    }

    @Test
    void verifyReturnsTheTokenId() {
        var issued = service.issue(SESSION, AGREEMENT);
        assertEquals(issued.tokenId(), service.verify(issued.token(), SESSION, AGREEMENT));
    }

    @Test
    void tokenFromAnotherSessionIsRejected() {
        var issued = service.issue(SESSION, AGREEMENT);
        assertThrows(ApiException.class, () -> service.verify(issued.token(), OTHER_SESSION, AGREEMENT));
    }

    @Test
    void tokenPresentedAgainstAnotherAgreementIsRejected() {
        var issued = service.issue(SESSION, AGREEMENT);
        assertThrows(ApiException.class, () -> service.verify(issued.token(), SESSION, "GL9999999999"));
    }

    @Test
    void tamperedSignatureIsRejected() {
        var issued = service.issue(SESSION, AGREEMENT);
        String[] parts = issued.token().split("\\.");
        String flipped = (parts[3].charAt(0) == 'A' ? "B" : "A") + parts[3].substring(1);
        assertThrows(ApiException.class,
                () -> service.verify(String.join(".", parts[0], parts[1], parts[2], flipped),
                        SESSION, AGREEMENT));
    }

    @Test
    void expiredTokenIsRejected() {
        props.getSecurity().setTokenTtlSeconds(60);
        var issued = service.issue(SESSION, AGREEMENT);
        String[] parts = issued.token().split("\\.");
        long backdated = Long.parseLong(parts[2]) - 120_000L;
        assertThrows(ApiException.class,
                () -> service.verify(String.join(".", parts[0], parts[1],
                        Long.toString(backdated), parts[3]), SESSION, AGREEMENT));
    }

    @Test
    void futureDatedTokenBeyondSkewIsRejected() {
        var issued = service.issue(SESSION, AGREEMENT);
        String[] parts = issued.token().split("\\.");
        long future = Long.parseLong(parts[2]) + 60_000L;
        assertThrows(ApiException.class,
                () -> service.verify(String.join(".", parts[0], parts[1],
                        Long.toString(future), parts[3]), SESSION, AGREEMENT));
    }

    @Test
    void malformedTokensAreRejected() {
        assertThrows(ApiException.class, () -> service.verify(null, SESSION, AGREEMENT));
        assertThrows(ApiException.class, () -> service.verify("", SESSION, AGREEMENT));
        assertThrows(ApiException.class, () -> service.verify("PAY1.abc.123", SESSION, AGREEMENT));
        assertThrows(ApiException.class, () -> service.verify("notatoken", SESSION, AGREEMENT));
        assertThrows(ApiException.class, () -> service.verify("PAY2.a.1.b", SESSION, AGREEMENT));
    }

    @Test
    void tokenDoesNotLeakTheSecret() {
        assertFalse(service.issue(SESSION, AGREEMENT).token()
                .contains(props.getSecurity().getTokenHmacSecret()));
    }

    /**
     * Length-prefixed signing means no rearrangement of field values produces a valid
     * alternative. With ":"-separated concatenation these two would collide.
     */
    @Test
    void fieldBoundariesCannotBeShifted() {
        var a = service.issue("SES_" + "c".repeat(64), "AB" + "C".repeat(10));
        assertThrows(ApiException.class,
                () -> service.verify(a.token(), "SES_" + "c".repeat(64) + "AB", "C".repeat(10)));
    }
}
