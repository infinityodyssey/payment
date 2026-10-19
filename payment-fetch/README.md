# payment-fetch

Agreement and customer details. **Read-only — this service writes nothing, anywhere.**

One capability, two front doors:

| Path | Caller | Auth | Agreement from | Data from |
|---|---|---|---|---|
| `POST /api/v1/payment/fetch-details` | browser | session cookie + `X-Session-Id`, nonce, encrypted body, borrower JWT | **the session** | session snapshot, LMS as fallback |
| `POST /api/v1/partner/fetch-details` | partner | bearer JWT, `aud=user`, role VENDOR or ADMIN | the request body | always a fresh LMS call |

Both land in `FetchService` and get the same validation, the same dues arithmetic, the
same vendor check and the same response shape.

## Why two paths and not one endpoint

Spring Security selects a filter chain **by path**. The two callers authenticate in
entirely different ways — one brings an encrypted body, a cookie paired with a header and
a nonce; the other brings plain JSON and a bearer token. A single path cannot apply both
sets of filters, so it would have to decide from the request which model to use, and the
only available signals are chosen by the caller. Send a bearer token and no cookie, and a
branching endpoint skips the session checks entirely.

Two paths make that choice structural rather than conditional. The shared filters already
assume the split: `SessionContextFilter` and `AntiReplayFilter` both exclude
`/api/v1/partner/` by prefix.

## Request

```json
{ "agreementNumber": "GL0001234567", "chargeType": "CURRENT_DUE", "paymentVendor": "PAYTM" }
```

`agreementNumber` means different things on the two paths:

- **Partner** — it *selects* the agreement, because a partner token is bound to none. A
  partner is authorised to act for many borrowers; what limits them is their role.
- **Borrower** — it selects **nothing**. The agreement comes from the session, where
  auth-service put it when it looked the borrower up to send the OTP. A value here that
  disagrees is logged and ignored. It is read only as a fallback when the session snapshot
  has expired, and even then the token's agreement hash has to match it.

Because the agreement is a server-side value in the normal path, there is no
caller-controlled reference to tamper with at all.

## The validation chain, in order

**No controller touches a header, a token, or a request attribute.** Everything below
happens in a filter or a service. The controllers declare `SessionContext` and
`TokenClaims` as method parameters and `VerifiedCallerArgumentResolver` supplies them —
so there is no lookup and no null check to copy into the next endpoint, and if a filter is
not mounted the resolver fails the request instead of passing a null along. Both
controllers are under 50 lines and contain one statement each.

Browser path:

| # | Where | Check |
|---|---|---|
| 1 | `AntiReplayFilter` | `X-Nonce` + `X-Timestamp`, 60s window, Redis `SETNX` |
| 2 | `SessionContextFilter` | `__Host-` cookie == `X-Session-Id` (CSRF double-submit), session key exists, TTL slides |
| 3 | `BorrowerAuthFilter` | JWT signature, algorithm pinned to HS256, issuer, audience, expiry, **and `sub` == this session**. Sets `ROLE_BORROWER`. |
| 4 | `SecurityConfig` | `hasAuthority(ROLE_BORROWER)` |
| 5 | decryption advice | AES-GCM body, AAD = session id |
| 6 | bean validation | shape and length of the DTO |
| 7 | `FetchService` | the session's agreement matched against the token's |
| 8 | `FetchService` | charge type, then vendor |

Partner path: `PartnerAuthFilter` (signature, algorithm, issuer, audience, expiry) →
`hasAnyAuthority(VENDOR, ADMIN)` → bean validation → charge type → vendor. No session, no
nonce, no encryption.

### Why the borrower token is checked in two places

The filter answers one question — **is this token valid for this session** — and sets
`ROLE_BORROWER` so the chain can require it. It cannot do more: the agreement lives inside
the AES-GCM encrypted body, and a servlet filter runs before the decryption advice.

The service then matches **the session's agreement against the token's**. Both are
server-side values written by auth-service at OTP time, so the borrower influences
neither. The one case where the request value is used — an expired snapshot — is still
gated by the token's agreement hash, so the fallback cannot reach another loan either.

Step 4 is what stops a *future* endpoint being reachable with no token at all: it is
enforced by the chain, so forgetting it is not silent.

`ROLE_SESSION` is deliberately **not** what gates this endpoint. A resolved session only
proves the browser completed the handshake, not that the OTP was ever verified.

The partner path has no equivalent second step. Its token carries no agreement, so once
the signature and role are good, it proceeds.

## Vendor checks hit the database every request — on purpose

A primary-key read on a ten-row table costs microseconds from Oracle's buffer cache, so a
cache would save nothing measurable, and it would cost the thing that matters: when
operations set `ACTIVE='N'` because a vendor is down, that has to take effect on the next
request, not after a TTL.

This is the **opposite** call from the token endpoint, where the work was 250ms of bcrypt
rather than a tiny indexed read. The lesson is not "never cache" or "always cache" — it is
that the cost has to be measured against what the cache breaks.

Three outcomes, kept distinct because collapsing them produces the ticket that says "it
just says error":

| | Status | Meaning |
|---|---|---|
| `VENDOR_INVALID` | 400 | Unknown code. A client bug, or someone probing. |
| `VENDOR_UNAVAILABLE` | 503 | Switched off. Temporary — pick another. |
| `VENDOR_AMOUNT_NOT_SUPPORTED` | 400 | Outside the vendor's limits. Change the amount. |

## The dues arithmetic

`AgreementMapper` is the legacy `getGoldDueDetails` logic ported rather than reinvented,
because it encodes rules written down nowhere else:

- Figures are **summed across `success[]`**, not read from `success[0]`. One customer can
  hold several gold-loan accounts and pays one total.
- Visit charges (`CCVCHGCD`, `CCVCHG`) are **subtracted** from `currentdue` and
  `otherOverdue`. The LMS includes them; this platform does not collect them.
- The **loss-amount branch**: any account at status `L` makes the customer `L`; otherwise
  a loss amount on a non-`L` account makes the status `LOSSAMT` and **replaces** the
  current due.
- Seized/repossessed sub-statuses override the status with `SR`, from
  `fetch.seized-sub-statuses`.

Two legacy defects are not carried over: `addrefid != ""` compared String references
rather than values, so the address branch was effectively random; and `Double.parseDouble`
on every amount accumulated binary rounding error into money. This uses `BigDecimal` and
`equals()` throughout. `AgreementMapperTest` locks all of it down — 15 cases.

One legacy behaviour **is** preserved and flagged in the code: the charge loop stops after
the first visit charge per account, so a second one on the same account is not subtracted.
That looks like a bug rather than a rule, but changing it changes what a borrower is
charged. Confirm with the business before removing the `break`.

## Still needs a decision

**`fetch.charge-type-amount` is empty.** Which figure each charge type is payable against
was never supplied, so `payableAmount` comes back `null` and the caller works from the
components. This is deliberate: the obvious-looking default, `TOTAL_OUTSTANDING`, is also
the largest, and a wrong guess here presents a borrower with a full closure amount when
they asked to pay one instalment. Fill the mapping and nothing else changes.

**`fetch.seized-sub-statuses` is empty** — set it from the legacy `GOLDLOANSEIZEDCASE`
value.

## Must not go in this module

- Any write. No payment initiation, no `tvstxnid`, no queue row.
- Deciding the amount to **charge**. The session snapshot is minutes old and the borrower
  may have paid elsewhere; `fromSessionSnapshot` on the response says which source was
  used. Payment initiation re-checks against a fresh LMS call regardless. Display is safe,
  charging is not.
- Session **state**. This service reads one Redis key (the AES key) and the snapshot. Only
  auth-service writes session state.

## Known gap

A multi-account customer served from the session snapshot sees only the first account's
figures, because auth-service stores `success[0]` rather than the whole array. A fresh LMS
call sums all of them correctly. If multi-account gold-loan customers are common, change
auth-service to store the full array — the mapper already handles it.
