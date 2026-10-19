# auth-service

Handshake, ECDH, OTP, browser and partner tokens. **Complete through OTP verification.**

The only service in the platform that writes session state.

## Endpoints

| | Auth | Encrypted |
|---|---|---|
| `POST /api/v1/auth/handshake` | none | no — establishes the key |
| `POST /api/v1/auth/verify-agreement` | cookie + header | yes |
| `POST /api/v1/auth/resend-otp` | cookie + header | yes |
| `POST /api/v1/auth/verify-otp` | cookie + header | yes |
| `POST /api/v1/partner/token` | API key + secret | no — mTLS, no ECDH |

Every browser call after the handshake needs, together: the `__Host-PAY_SESSION` cookie,
an identical `X-Session-Id` header, `X-Nonce` and `X-Timestamp`, and an AES-GCM body.
Missing any one is a rejection, not a downgrade.

## The Redis contract this service owns

```
session:state:{sid}   hash: data + version   WRITTEN AND READ HERE ONLY
session:key:{sid}     AES key hex            written here, read by fetch and payment
claim:token-issue:{sid}
otp:code|attempts|cooldown|sends|lock:*
```

Both session keys share a TTL and are deleted together, so a downstream key read is a
reliable liveness check without any session-state access.

## Three things that are load-bearing

**The attempt cap.** Three guesses against 100,000 values is what makes a 5-digit code
acceptable. Raising `auth.otp.max-attempts`, removing the lockout, or changing the
counter reset in `OtpService.issue` are security changes, not tuning.

**The pepper, not the hash cost.** A 5-digit space is exhaustible instantly by anyone who
has the pepper. It lives in the secret store, not Redis — a Redis dump alone yields
nothing.

**Compare-and-swap on session writes.** `SessionWriteService.save` runs a Lua script that
writes only if the version still matches. Without it, two concurrent verifications each
issue a token and the second write erases the first, leaving the borrower with a
credential that fails on first use.

## Running it

```bash
export PAYMENT_TOKEN_HMAC_SECRET="$(openssl rand -base64 48)"
export PAYMENT_OTP_PEPPER="$(openssl rand -base64 48)"
export ACTUATOR_PASSWORD="$(openssl rand -base64 24)"
export ORACLE_URL=jdbc:oracle:thin:@//host:1521/SERVICE
export ORACLE_USER=PAYMENT_APP ORACLE_PASSWORD=... ORACLE_SCHEMA=PAYMENT
export REDIS_HOST=... ALLOWED_ORIGINS=https://localhost:4200
export GOLDLOAN_TOKEN_URL=... GOLDLOAN_FETCH_URL=...
export SMS_TOKEN_URL=... SMS_SEND_URL=...

mvn -pl payment-shared,payment-auth spring-boot:run -Dspring-boot.run.profiles=dev
```

Startup fails without the three secrets, or if any is under 32 bytes or looks like a
placeholder. TLS is required even locally: the session cookie is `__Host-` prefixed and
`Secure`, so a browser on plain HTTP discards it.

## Still needs your input

Two methods where your existing code was not available, each isolated so the surrounding
logic does not change:

- `SmsApiClient.token()` — `getSmsAccessToken()`'s request shape was not
  shared. Currently a bare POST reading `auth.sms.token-json-field`.
- `LmsApiClient.token()` in `payment-shared` — same for `getEXApiToken(fiid)`.

Also set `auth.sms.message-template` to your DLT-registered wording. Startup fails if it
does not contain `{otp}`.

## Known risk carried from the existing integration

The SMS gateway takes the message as a GET query parameter, so the OTP and mobile number
land in the access logs of every hop. Everything else here is built so the OTP never
reaches a log; this one hop defeats it and cannot be fixed from our side. See the class
comment on `SmsApiClient`.
