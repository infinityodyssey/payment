# payment-fetch

Agreement and customer details. **Read-only — this service writes nothing, anywhere.**

One capability, two front doors:

| Path | Caller | Auth | Agreement from | Data from |
|---|---|---|---|---|
| `POST /api/v1/payment/fetch-details` | browser | session cookie + `X-Session-Id`, nonce, encrypted body, borrower JWT | the token binding | session snapshot, LMS as fallback |
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

`agreementNumber` means different things on the two paths, and this is the part worth
understanding:

- **Borrower** — it *proves* the token binding, it does not select the data.
  `verifyBorrowerToken` recomputes the keyed agreement hash from this value and compares
  it with the one inside the signature. Changing it to someone else's agreement fails
  before any data is fetched.
- **Partner** — it *selects* the agreement, because a partner token is bound to none. A
  partner is authorised to act for many borrowers; what limits them is their role.

## The validation chain, in order

1. Bean validation on the DTO — shape and length only.
2. **Browser:** cookie/header pair (CSRF), nonce + timestamp (replay), session key exists
   (`SessionContextFilter`), body decrypts. **Partner:** JWT signature, algorithm pinned
   to HS256, issuer, audience, expiry (`PartnerAuthFilter`), role matched in the chain.
3. Borrower JWT verified against **both** the session and the agreement, in `FetchService`.
   `ROLE_SESSION` from the filter only means "a live session resolved" — it is not
   authorisation to fetch, because a session alone proves the handshake happened, not that
   the OTP was ever verified.
4. Charge type against `fetch.allowed-charge-types`.
5. Vendor against `TVSCS_PAYMENT_VENDOR_PG`: exists, active, accepts the amount.

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
