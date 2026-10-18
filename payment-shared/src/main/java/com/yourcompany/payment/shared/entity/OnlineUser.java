package com.yourcompany.payment.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A machine account that can request a token: username, password, active flag, role.
 *
 * The role lives HERE and nowhere else. It is read from this row at token issue and
 * written into the JWT. A role arriving in the request is never trusted — see
 * PartnerTokenService.
 *
 * READ ONLY. There is deliberately no "last token issued" column: that would be an
 * UPDATE on the same row for every token request, and a partner that fetches a token per
 * call would then have six servers queueing on one row lock. The audit ledger already
 * records every issue, so the question "when did this user last get a token" is a query
 * against that, not a column here.
 */
@Entity
@Table(name = "TVSCS_ONLINE_USER_PG")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineUser {

    @Id
    @Column(name = "USERNAME", length = 64, nullable = false)
    private String username;

    /**
     * BCrypt hash, never the password. The column is named PASSWORD because that is what
     * it holds conceptually, but a plaintext value in it means one SELECT grant, one
     * export, or one developer with read access walks away with every partner's
     * credential. Generate with cost 12 or higher.
     */
    @Column(name = "PASSWORD", length = 100, nullable = false)
    private String password;

    /** 'Y' or 'N'. Checked on every token issue. */
    @Column(name = "ACTIVE", length = 1, nullable = false)
    private String active;

    /** ADMIN or VENDOR. Goes into the JWT verbatim as ROLE_ADMIN / ROLE_VENDOR. */
    @Column(name = "ROLE", length = 16, nullable = false)
    private String role;

    @Column(name = "CREATED_AT")
    private Instant createdAt;

    public boolean isActive() {
        return "Y".equalsIgnoreCase(active);
    }
}
