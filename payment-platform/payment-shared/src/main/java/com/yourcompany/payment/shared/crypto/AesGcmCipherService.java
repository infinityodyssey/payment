package com.yourcompany.payment.shared.crypto;

import com.yourcompany.payment.shared.dto.EncryptedEnvelope;
import com.yourcompany.payment.shared.exception.CryptoException;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-256-GCM, fresh 96-bit IV per message, 128-bit tag, session id as AAD. */
@Service
public class AesGcmCipherService {

    public static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedEnvelope encrypt(String plaintext, byte[] key, String aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }

            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int ctLen = out.length - TAG_BYTES;

            return EncryptedEnvelope.builder()
                    .ivHex(Hex.toHexString(iv))
                    .ciphertextHex(Hex.toHexString(Arrays.copyOfRange(out, 0, ctLen)))
                    .authTagHex(Hex.toHexString(Arrays.copyOfRange(out, ctLen, out.length)))
                    .build();
        } catch (Exception e) {
            // Never log key material or plaintext.
            throw new CryptoException("Payload encryption failed");
        }
    }

    public String decrypt(EncryptedEnvelope envelope, byte[] key, String aad) {
        try {
            byte[] iv = Hex.decode(envelope.getIvHex());
            byte[] ct = Hex.decode(envelope.getCiphertextHex());
            byte[] tag = Hex.decode(envelope.getAuthTagHex());

            if (iv.length != IV_BYTES || tag.length != TAG_BYTES) {
                throw new CryptoException("Malformed encrypted payload");
            }

            byte[] combined = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, combined, 0, ct.length);
            System.arraycopy(tag, 0, combined, ct.length, tag.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            // Tag mismatch, wrong key, or tampering. Fatal for the request, never a
            // reason to fall back to plaintext.
            throw new CryptoException("Payload authentication failed");
        }
    }
}
