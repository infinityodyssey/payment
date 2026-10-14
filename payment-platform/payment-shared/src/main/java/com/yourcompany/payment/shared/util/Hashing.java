package com.yourcompany.payment.shared.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Truncated SHA-256 for keys and references. Never used for secrets. */
public final class Hashing {

    private Hashing() {}

    public static String shortHash(String value) {
        return hex(value, 16);
    }

    public static String keyHash(String value) {
        return hex(value, 12);
    }

    private static String hex(String value, int bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
