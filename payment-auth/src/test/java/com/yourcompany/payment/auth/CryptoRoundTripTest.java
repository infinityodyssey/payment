package com.yourcompany.payment.auth;

import com.yourcompany.payment.shared.crypto.AesGcmCipherService;
import com.yourcompany.payment.shared.crypto.EcdhKeyService;
import com.yourcompany.payment.shared.dto.EncryptedEnvelope;
import com.yourcompany.payment.shared.exception.CryptoException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoRoundTripTest {

    private final EcdhKeyService ecdh = new EcdhKeyService();
    private final AesGcmCipherService cipher = new AesGcmCipherService();

    private byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void bothSidesDeriveTheSameKey() {
        KeyPair client = ecdh.generateEphemeralKeyPair();
        KeyPair server = ecdh.generateEphemeralKeyPair();

        byte[] serverSide = ecdh.deriveSessionKey(server.getPrivate(), client.getPublic(), "SES_t");
        byte[] clientSide = ecdh.deriveSessionKey(client.getPrivate(), server.getPublic(), "SES_t");

        assertArrayEquals(serverSide, clientSide);
        assertEquals(32, serverSide.length);
    }

    @Test
    void differentSessionIdsProduceDifferentKeys() {
        KeyPair client = ecdh.generateEphemeralKeyPair();
        KeyPair server = ecdh.generateEphemeralKeyPair();
        assertFalse(Arrays.equals(
                ecdh.deriveSessionKey(server.getPrivate(), client.getPublic(), "SES_a"),
                ecdh.deriveSessionKey(server.getPrivate(), client.getPublic(), "SES_b")));
    }

    @Test
    void roundTripsPayload() {
        byte[] key = randomKey();
        EncryptedEnvelope envelope = cipher.encrypt("{\"otp\":\"12345\"}", key, "SES_a");
        assertEquals("{\"otp\":\"12345\"}", cipher.decrypt(envelope, key, "SES_a"));
    }

    @Test
    void ciphertextFromOneSessionCannotBeUsedInAnother() {
        byte[] key = randomKey();
        EncryptedEnvelope envelope = cipher.encrypt("{\"amount\":100}", key, "SES_a");
        // Same key, different AAD: the tag check must fail.
        assertThrows(CryptoException.class, () -> cipher.decrypt(envelope, key, "SES_b"));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] key = randomKey();
        EncryptedEnvelope envelope = cipher.encrypt("{\"amount\":100}", key, "SES_a");
        String ct = envelope.getCiphertextHex();
        envelope.setCiphertextHex((ct.charAt(0) == 'a' ? "b" : "a") + ct.substring(1));
        assertThrows(CryptoException.class, () -> cipher.decrypt(envelope, key, "SES_a"));
    }

    @Test
    void invalidClientPublicKeysAreRejected() {
        assertThrows(CryptoException.class, () -> ecdh.parseAndValidatePublicKey("04" + "00".repeat(64)));
        assertThrows(CryptoException.class, () -> ecdh.parseAndValidatePublicKey("deadbeef"));
        assertThrows(CryptoException.class, () -> ecdh.parseAndValidatePublicKey(""));
    }

    @Test
    void ivIsFreshPerMessage() {
        byte[] key = randomKey();
        EncryptedEnvelope first = cipher.encrypt("same", key, "SES_a");
        EncryptedEnvelope second = cipher.encrypt("same", key, "SES_a");
        assertFalse(first.getIvHex().equals(second.getIvHex()));
        assertFalse(first.getCiphertextHex().equals(second.getCiphertextHex()));
    }
}
