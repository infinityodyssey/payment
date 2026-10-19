package com.yourcompany.payment.auth.service;

import com.yourcompany.payment.auth.controller.PartnerTokenController;
import com.yourcompany.payment.shared.entity.OnlineUser;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.repo.OnlineUserRepository;
import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.security.Role;
import com.yourcompany.payment.shared.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Username and password in, JWT out:
 *
 *   1. look the user up in TVSCS_ONLINE_USER_PG
 *   2. is ACTIVE = 'Y'
 *   3. does the password match the bcrypt hash
 *   4. issue a 15-minute JWT carrying the role FROM THE ROW
 *
 * No cache, no state. Nothing is written anywhere — the grant on TVSCS_ONLINE_USER_PG is
 * SELECT only, and there is no audit row. Every request runs the same four steps.
 *
 * <h2>The role in the request is never used</h2>
 * The request may carry a role, because existing clients send one. It is compared
 * against the database, logged on mismatch, and discarded — the JWT's role always comes
 * from TVSCS_ONLINE_USER_PG.ROLE. If it came from the request, a vendor who knows their
 * own password could ask for ADMIN and get it, and the password would be protecting
 * nothing.
 *
 * <h2>Cost per call, so the number is on the record</h2>
 * The SELECT is a primary-key read Oracle serves from its buffer cache in well under a
 * millisecond. Bcrypt at cost 12 is about 250ms of CPU, which does not overlap: one core
 * answers roughly four token requests a second.
 *
 * That is fine as long as partners hold the token for its life instead of fetching one
 * per call — which is what the fifteen-minute expiry and the expiresInSeconds field in
 * the response are for. Fifteen minutes of reuse turns hundreds of requests a minute
 * into four an hour.
 *
 * It stops being fine if a partner re-authenticates on every call. At 600 transactions a
 * minute that is 2.5 cores of the platform doing nothing but hashing, and double that at
 * the next volume step. There is no server-side cache to absorb it any more, so the
 * controls are: confirm in the integration contract that partners cache the token, watch
 * the token-request rate (if it tracks transaction volume, somebody is not caching), and
 * have the gateway rate-limit POST /api/v1/partner/token.
 *
 * If a partner genuinely cannot cache, the lever is the bcrypt cost rather than a cache.
 * A work factor exists to compensate for low-entropy passwords; against a generated
 * 32-byte secret, cost 10 is just as infeasible to brute-force and four times faster.
 * That needs the secrets to actually be generated, not chosen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerTokenService {

    /** Bcrypt hash of a value nobody has, so an unknown username costs the same time. */
    private static final String DUMMY_HASH =
            "$2a$12$usesomesillystringfore7hnbRJcxd0cQVGdfj.9jXq7L1XgkQ2i";

    private final OnlineUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ApiKeyCodec apiKeyCodec;
    private final ClientContext clientContext;

    public PartnerTokenController.PartnerTokenResponse issue(
            PartnerTokenController.PartnerTokenRequest request, HttpServletRequest http) {

        Credentials credentials = resolve(request);
        Optional<OnlineUser> found = users.findByUsername(credentials.username());

        // Always run the encoder, including for an unknown username, so response time
        // does not reveal which usernames exist. Without this, an attacker enumerates
        // valid accounts by timing alone — the step before guessing passwords.
        boolean passwordMatches = passwordEncoder.matches(
                credentials.password(),
                found.map(OnlineUser::getPassword).orElse(DUMMY_HASH));

        if (found.isEmpty() || !passwordMatches) {
            throw reject(credentials.username(), "AUTH_FAILED", http);
        }

        OnlineUser user = found.get();

        if (!user.isActive()) {
            // Same response as a wrong password. Saying the account is merely inactive
            // confirms the username is real.
            throw reject(credentials.username(), "USER_INACTIVE", http);
        }

        String authority = Role.fromDatabase(user.getRole());
        if (authority == null) {
            // A role the code does not recognise would produce a token that authorises
            // nothing, which looks like a platform fault from the partner's side.
            log.error("User {} has unrecognised role '{}' in TVSCS_ONLINE_USER_PG",
                    credentials.username(), user.getRole());
            throw reject(credentials.username(), "ROLE_INVALID", http);
        }

        if (request.getRole() != null && !request.getRole().isBlank()
                && !authority.equals(Role.fromDatabase(request.getRole()))) {
            log.warn("User {} requested role '{}' but is '{}' in the database; using the database",
                    credentials.username(), request.getRole(), user.getRole());
        }

        var issued = jwtService.issueUserToken(user.getUsername(), authority);

        // The record that a token was issued. Nothing goes to Oracle, so this log line
        // and its WARN counterpart below are the whole audit trail for this endpoint.
        log.info("Token issued user={} role={} jti={}",
                user.getUsername(), authority, issued.tokenId());

        return PartnerTokenController.PartnerTokenResponse.builder()
                .accessToken(issued.token())
                .tokenType("Bearer")
                .expiresInSeconds(issued.expiresInSeconds())
                .role(user.getRole())
                .build();
    }

    /**
     * Either username + password, or a single encrypted key holding both.
     *
     * The encrypted key is packaging, not extra security: the same secret over the same
     * TLS, with a shared AES key to manage on top. Offer it where a partner prefers one
     * opaque string; do not describe it as stronger.
     */
    private Credentials resolve(PartnerTokenController.PartnerTokenRequest request) {
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            return apiKeyCodec.decode(request.getApiKey());
        }
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw authFailed();
        }
        return new Credentials(request.getUsername().trim(), request.getPassword());
    }

    /**
     * The only record of a failed authentication, now that nothing is written to Oracle.
     * WARN rather than INFO on purpose: this is the line an alert counts to notice
     * someone grinding a partner password, and it is the only place that signal exists.
     * The caller still gets one undifferentiated message.
     */
    private ApiException reject(String username, String reason, HttpServletRequest http) {
        log.warn("Token request refused user={} reason={} ip={}",
                username, reason, clientContext.clientIp(http));
        return authFailed();
    }

    private ApiException authFailed() {
        return new ApiException("AUTH_FAILED", "Authentication failed.", HttpStatus.UNAUTHORIZED);
    }

    public record Credentials(String username, String password) {}
}
