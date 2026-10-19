package com.yourcompany.payment.auth.service;

import com.yourcompany.payment.auth.config.AuthProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The optional "one unique encrypted key" form of the credential: username and password
 * packed into a single opaque string.
 *
 * AES-256-GCM, key derived from auth.api-key-secret, layout:
 *
 *   base64url( 12-byte IV | ciphertext | 16-byte GCM tag )     plaintext = "username:password"
 *
 * GCM rather than CBC so a tampered key fails the tag check instead of decrypting to
 * garbage that then gets parsed.
 *
 * BE CLEAR ABOUT WHAT THIS IS. It is packaging, not extra security. The same password
 * travels over the same TLS, and now there is a shared AES key to distribute and rotate
 * as well. It is worth offering because one opaque string is easier for a partner to
 * handle than two fields, and for no other reason. Nobody should be told it is stronger
 * than sending username and password.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyCodec {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final AuthProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Generate a key for a partner. Run it once at onboarding, hand over the output. */
    public String encode(String username, String password) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal((username + ":" + password).getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public PartnerTokenService.Credentials decode(String apiKey) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(apiKey.trim());
            if (raw.length <= IV_BYTES + (TAG_BITS / 8)) {
                throw authFailed();
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            String plain = new String(
                    cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);

            // Split on the FIRST colon only: a password may contain one.
            int split = plain.indexOf(':');
            if (split <= 0 || split == plain.length() - 1) {
                throw authFailed();
            }
            return new PartnerTokenService.Credentials(
                    plain.substring(0, split), plain.substring(split + 1));

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // A failed tag check, bad base64 and a wrong key all land here and all look
            // identical to the caller.
            log.warn("API key could not be decoded: {}", e.getClass().getSimpleName());
            throw authFailed();
        }
    }

    /**
     * SHA-256 of the configured secret, so any secret length yields a valid AES-256 key.
     * The secret still has to be long: see AuthStartupGuard.
     */
    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(props.getApiKeySecret().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private ApiException authFailed() {
        return new ApiException("AUTH_FAILED", "Authentication failed.", HttpStatus.UNAUTHORIZED);
    }
}
