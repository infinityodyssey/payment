package com.yourcompany.payment.shared.security;

/**
 * The two roles. They come from TVSCS_ONLINE_USER_PG.ROLE and go into the JWT.
 *
 * The database stores ADMIN / VENDOR; the JWT and Spring Security carry the ROLE_
 * prefix, because that is what hasAuthority() matches on.
 */
public final class Role {

    private Role() {}

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String VENDOR = "ROLE_VENDOR";

    /** Borrower journey. Not stored in the user table — issued after OTP verification. */
    public static final String BORROWER = "ROLE_BORROWER";

    /** DB value ('VENDOR') to authority ('ROLE_VENDOR'). Null for anything unrecognised. */
    public static String fromDatabase(String dbRole) {
        if (dbRole == null) {
            return null;
        }
        return switch (dbRole.trim().toUpperCase()) {
            case "ADMIN" -> ADMIN;
            case "VENDOR" -> VENDOR;
            default -> null;
        };
    }

    public static boolean isKnown(String authority) {
        return ADMIN.equals(authority) || VENDOR.equals(authority) || BORROWER.equals(authority);
    }
}
