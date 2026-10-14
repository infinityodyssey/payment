package com.yourcompany.payment.shared.util;

/**
 * Masking for anything that reaches a log line.
 *
 * Rule for this platform: OTPs, payment tokens and full agreement numbers never appear
 * in a log at any level, including DEBUG. Everything else identifying a borrower goes
 * through here first.
 */
public final class Mask {

    private Mask() {}

    public static String agreement(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    public static String mobile(String value) {
        if (value == null || value.length() < 4) {
            return "******";
        }
        return "XXXXXX" + value.substring(value.length() - 4);
    }

    public static String sessionTail(String value) {
        if (value == null || value.length() < 8) {
            return "?";
        }
        return "..." + value.substring(value.length() - 8);
    }
}
