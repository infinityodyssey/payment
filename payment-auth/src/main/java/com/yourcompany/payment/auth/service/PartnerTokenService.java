package com.yourcompany.payment.auth.service;

import com.yourcompany.payment.auth.config.AuthProperties;
import com.yourcompany.payment.auth.controller.PartnerTokenController;
import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.entity.OnlineUser;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.repo.OnlineUserRepository;
import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.security.Role;
import com.yourcompany.payment.shared.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Username and password in, JWT out:
 *
 *   1. look the user up in TVSCS_ONLINE_USER_PG
 *   2. is ACTIVE = 'Y'
 *   3. does the password match the bcrypt hash
 *   4. issue a 15-minute JWT carrying the role FROM THE ROW
 *
 * Nothing is written to Oracle at all — the grant on TVSCS_ONLINE_USER_PG is SELECT
 * only, and token issue writes no audit row either. At a token per call that would be
 * hundreds of INSERTs a minute to record something the JWT already describes.
 *
 * THE LOG IS THEREFORE THE ONLY RECORD of partner authentication. Two lines matter:
 * "Token issued" at INFO, and "Token request refused" at WARN. Both go to stdout, into
 * Loki, and the refusal line is what an alert counts to notice someone grinding a
 * password. If Loki retention is not actually configured, there is no record anywhere
 * that a partner ever authenticated — which is a question an auditor does ask.
 *
 * <h2>The role in the request is never used</h2>
 * The request may carry a role, because existing clients send one. It is compared
 * against the database, logged on mismatch, and discarded — the JWT's role always comes
 * from TVSCS_ONLINE_USER_PG.ROLE. If it came from the request, a vendor who knows their
 * own password could ask for ADMIN and get it, and the password would be protecting
 * nothing.
 *
 * <h2>What actually costs money here: bcrypt, not the database</h2>
 * The SELECT is a primary-key read Oracle serves from its buffer cache in well under a
 * millisecond. Bcrypt at cost 12 is about 250ms of pure CPU — a thousand times more, and
 * it is CPU, so it does not overlap. One core answers roughly four token requests a
 * second. At the month-end rate, a partner that fetches a token per call instead of
 * caching it would need several cores doing nothing but hashing.
 *
 * <b>The intended fix is on the partner's side: cache the token for its life.</b> The
 * response carries expiresInSeconds for exactly that reason. Fifteen minutes of reuse
 * turns hundreds of token requests a minute into four an hour.
 *
 * Because that cannot be relied on, the cache below makes the unoptimised case cheap
 * too. A repeat request with the same correct credentials returns the token already
 * issued, skipping both the SELECT and the bcrypt. A wrong password cannot hit the cache
 * — the key is derived from the password — so guessing still pays the full 250ms, which
 * is what keeps bcrypt useful as the brake.
 *
 * Caching an already-valid token does not widen the revocation window by one second:
 * the token being returned would have been accepted for that long anyway, because
 * verification is stateless. ACTIVE='N' takes effect when the token expires, cache or no
 * cache.
 *
 * Also ask the gateway to rate-limit POST /api/v1/partner/token. It is the one endpoint
 * where a misbehaving client can spend real CPU, and the gateway is the right place to
 * bound it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerTokenService {

    /** Bcrypt hash of a value nobody has, so an unknown username costs the same time. */
    private static final String DUMMY_HASH =
            "$2a$12$usesomesillystringfore7hnbRJcxd0cQVGdfj.9jXq7L1XgkQ2i";

    private static final String CACHE_PREFIX = "pay:issued-token:";

    /**
     * Mint a new token rather than reusing a cached one below this much remaining life.
     * A partner handed a token with 30 seconds left will fail mid-call and blame us.
     */
    private static final double REFRESH_BELOW = 0.25;

    private final OnlineUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ApiKeyCodec apiKeyCodec;
    private final StringRedisTemplate redis;
    private final ClientContext clientContext;
    private final SharedProperties shared;
    private final AuthProperties auth;

    public PartnerTokenController.PartnerTokenResponse issue(
            PartnerTokenController.PartnerTokenRequest request, HttpServletRequest http) {

        Credentials credentials = resolve(request);
        String cacheKey = cacheKey(credentials);

        Cached cached = fromCache(cacheKey);
        if (cached != null) {
            // No SELECT, no bcrypt, no write anywhere. The log line below is the record.
            log.info("Token reused user={} role={} remaining={}s",
                    credentials.username(), cached.role(), cached.remainingSeconds());
            return PartnerTokenController.PartnerTokenResponse.builder()
                    .accessToken(cached.token())
                    .tokenType("Bearer")
                    // The REMAINING life, not the full TTL. A client told 900 seconds
                    // when 200 are left will keep using a dead token.
                    .expiresInSeconds(cached.remainingSeconds())
                    .role(cached.role())
                    .build();
        }

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
        toCache(cacheKey, issued.token(), user.getRole(), issued.expiresInSeconds());

        log.info("Token issued user={} role={} jti={}", user.getUsername(), authority, issued.tokenId());

        return PartnerTokenController.PartnerTokenResponse.builder()
                .accessToken(issued.token())
                .tokenType("Bearer")
                .expiresInSeconds(issued.expiresInSeconds())
                .role(user.getRole())
                .build();
    }

    // ===================================================================== cache

    /**
     * The key carries a keyed hash of the password, which is what makes the cache safe:
     * a wrong password produces a different key, misses, and pays the full bcrypt cost.
     * There is no code path that returns a cached token to a caller who did not present
     * the credentials it was issued for.
     *
     * A Redis dump yields HMAC(secret, user:password), not the password, and not even a
     * guessable hash without the signing secret.
     */
    private String cacheKey(Credentials credentials) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    shared.getSecurity().getPartnerTokenSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            mac.update(credentials.username().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            byte[] digest = mac.doFinal(credentials.password().getBytes(StandardCharsets.UTF_8));
            return CACHE_PREFIX + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Cached fromCache(String key) {
        if (!auth.isReuseIssuedToken()) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            Long remaining = redis.getExpire(key);
            if (value == null || remaining == null || remaining <= 0) {
                return null;
            }
            // Too little life left to be worth handing over.
            if (remaining < shared.getSecurity().getPartnerTokenTtlSeconds() * REFRESH_BELOW) {
                return null;
            }
            int split = value.indexOf('|');
            if (split <= 0) {
                return null;
            }
            return new Cached(value.substring(split + 1), value.substring(0, split), remaining);
        } catch (RuntimeException e) {
            // Redis down: fall through to the database and bcrypt. Slower, still correct.
            // This is an optimisation, never a dependency.
            log.warn("Issued-token cache unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void toCache(String key, String token, String role, long ttlSeconds) {
        if (!auth.isReuseIssuedToken()) {
            return;
        }
        try {
            redis.opsForValue().set(key, role + "|" + token, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.warn("Could not cache issued token: {}", e.getClass().getSimpleName());
        }
    }

    // ================================================================== plumbing

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

    private record Cached(String token, String role, long remainingSeconds) {}
}
