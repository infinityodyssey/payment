# payment-service — SKELETON

Browser payment lifecycle, vendor adapters, gateway callbacks.

## Belongs here
- `POST /api/v1/payment/initiate` — idempotent per session via `SETNX payment:init:{sid}`
- `GET /api/v1/payment/vendors` — backend-computed eligibility; the frontend never decides
- `POST /api/v1/payment/select-vendor` — creates the attempt, generates `tvstxnid`
- `GET /api/v1/payment/status/{tvstxnid}` — **no session required**, ownership checked
  against `PAYMENT_ATTEMPT.SESSION_REF`
- `POST /api/v1/payment/webhook/{vendorCode}` — gateway callbacks

## The two controls that matter most
**Webhook signature verification.** The legacy implementation had a
`verifyGatewaySignature` whose entire body was `return true;`, and the caller ignored the
result. That is a forgeable payment success reachable by anyone who can guess a
transaction id. Verify the HMAC or checksum with a constant-time compare, enforce a
timestamp window, and never trust the browser redirect.

**Server-side amount validation.** Both legacy backends took the amount from the client
with only a `@DecimalMin("1.00")` check. Validate against LMS dues with a hard ceiling.

## Callback constraints
- No session, no token. Resolve by `tvstxnid` in Oracle — a borrower may take longer at
  the gateway than the 5-minute session lives.
- Finish well inside the gateway's timeout (typically ~60s). Touch only Oracle and Kafka,
  **never the LMS**.
- Idempotent: `UPDATE ... WHERE STATUS='INITIATED'`. Zero rows means already handled —
  return 200 anyway, or the gateway retries harder.
- On success, write the `PAYMENT_POSTING_QUEUE` row and publish `posting.requested`.
  This service never calls the LMS posting endpoint.
