package com.yourcompany.payment.shared.crypto;

import com.yourcompany.payment.shared.exception.CryptoException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;

import javax.crypto.KeyAgreement;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

/**
 * Ephemeral ECDH on P-256, interoperable with the browser Web Crypto API.
 *
 * The server private key is generated, used once to derive the session key, then
 * dropped. It is never stored in Redis and never written to Oracle, so no later
 * database compromise can retroactively decrypt captured traffic.
 */
@Service
public class EcdhKeyService {

    private static final String CURVE = "secp256r1";
    private static final int AES_KEY_BYTES = 32;
    private static final byte[] HKDF_INFO =
            "payment-session-aes-256-gcm/v1".getBytes(StandardCharsets.UTF_8);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            kpg.initialize(new ECGenParameterSpec(CURVE));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("Unable to generate key pair");
        }
    }

    public String exportPublicKeyHex(PublicKey publicKey) {
        if (publicKey instanceof org.bouncycastle.jce.interfaces.ECPublicKey bc) {
            return Hex.toHexString(bc.getQ().getEncoded(false));
        }
        throw new CryptoException("Unsupported key type");
    }

    /**
     * Parses and fully validates a client public key.
     *
     * decodePoint already rejects points off the curve; the explicit checks remain
     * because an invalid-curve or small-order key is the classic way to coerce a
     * predictable shared secret out of an ECDH endpoint.
     */
    public PublicKey parseAndValidatePublicKey(String rawHex) {
        try {
            byte[] encoded = Hex.decode(rawHex.trim());
            if (encoded.length != 65 || encoded[0] != 0x04) {
                throw new CryptoException("Invalid client public key");
            }

            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE);
            ECPoint point = spec.getCurve().decodePoint(encoded).normalize();

            if (point.isInfinity() || !point.isValid()) {
                throw new CryptoException("Invalid client public key");
            }
            if (!point.multiply(spec.getN()).isInfinity()) {
                throw new CryptoException("Invalid client public key");
            }

            KeyFactory kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            return kf.generatePublic(new ECPublicKeySpec(point, spec));
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("Invalid client public key");
        }
    }

    /** HKDF-SHA256 with the session id as salt, so two sessions never share a key. */
    public byte[] deriveSessionKey(PrivateKey serverPrivate, PublicKey clientPublic, String sessionId) {
        byte[] shared = null;
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            ka.init(serverPrivate);
            ka.doPhase(clientPublic, true);
            shared = ka.generateSecret();

            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(shared, sessionId.getBytes(StandardCharsets.UTF_8), HKDF_INFO));

            byte[] aesKey = new byte[AES_KEY_BYTES];
            hkdf.generateBytes(aesKey, 0, aesKey.length);
            return aesKey;
        } catch (Exception e) {
            throw new CryptoException("Key derivation failed");
        } finally {
            if (shared != null) {
                Arrays.fill(shared, (byte) 0);
            }
        }
    }
}
