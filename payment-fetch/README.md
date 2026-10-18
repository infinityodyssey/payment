# fetch-service — SKELETON

Agreement and customer details from the LMS. Read-only.

## Belongs here
- `GET /api/v1/customer/details` — agreement details for the verified session
- `GET /api/v1/partner/agreement/{n}` — scoped partner lookup (see the open question below)
- LMS response mapping beyond the four auth fields: dues, charges, addresses

## Read the session snapshot before calling the LMS

`auth-service` stores the agreement-fetch response at `session:lms:{sid}` when it looks up
the mobile number for OTP dispatch. Read it through `LmsSnapshotStore` and you save one
LMS call per journey — and the borrower sees the same data the OTP was sent against.

```java
lmsSnapshots.get(sessionId)
    .map(LmsSnapshot::getRawAgreementJson)     // map dues, charges, address from here
    .orElseGet(() -> lmsClient.lookupWithPayload(agreement) ... )   // miss = one call
```

A miss costs one LMS call, which is the behaviour without the cache at all. Never fail a
request because the snapshot is absent.

**Scope is one session, not one agreement.** Two sessions for the same borrower hold
separate snapshots and may disagree — each shows what was true when it started. That is
correct.

**Safe for display. Not safe for deciding how much to charge.** If the borrower pays
through another channel between OTP and the payment screen, the dues in the snapshot are
stale. Payment initiation must validate the amount against a fresh LMS call.

## Must not appear here
- Any write to `session:state`. Only auth-service writes it. This service reads a token
  signature and `session:key`, nothing else.
- A cache of agreement data used for OTP dispatch. A stale mobile number sends a
  borrower's code to a phone they may no longer control. Caching for a dues *display* is
  fine, keyed separately from anything auth uses.

## Wiring needed
1. `SecurityConfig` modelled on auth's, with `/api/v1/customer/**` requiring `ROLE_SESSION`
2. `FilterRegistrationConfig` — copy from auth, or the shared filters run twice
3. `RestClientConfig` with the `lmsRestClient` bean
4. Token verification: `paymentTokenService.verify(bearer, sessionId, agreementNumber)`
   where `agreementNumber` comes from the request

## Open question that blocks the partner endpoint
Is the partner agreement fetch scoped to their own book, or unscoped? Unscoped means a
partner key retrieves borrower name, mobile and dues for any agreement number, with no
borrower involvement — which routes around every OTP control in the platform.
