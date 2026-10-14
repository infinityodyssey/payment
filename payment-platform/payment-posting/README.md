# posting-service — SKELETON

Partner direct posting intake.

## Belongs here
- `POST /api/v1/partner/payment/posting`
- `GET /api/v1/partner/posting/{reference}` — status lookup for a partner whose request
  timed out

## The transaction that defines this service
```
validate → generate challan (Oracle sequence) → INSERT status=PENDING → COMMIT → respond
```

All one transaction. Never generate the challan, respond, then write — a crash in that
gap leaves the partner holding a challan for a payment you have no record of, unfindable
by reconciliation because it does not exist.

The same `idempotencyKey` must always return the **same challan number**. That is what
`UQ_PPQ_PARTNER (PARTNER_CODE, PARTNER_IDEM_KEY)` is for: one line of DDL that the whole
integration rests on.

## Must not appear here
- Any call to the LMS. This service writes to the queue; `payment-worker` posts.
- ECDH or envelope handling. Partners are machines over mTLS; `@SkipEncryption` at class
  level, as in `PartnerTokenController`.

## Contract to agree in writing with each partner
The challan number confirms **you have accepted and recorded the payment**, not that the
loan account is updated. Those are minutes apart normally and hours during an LMS outage.
If their customer-facing message says "loan updated", that is a promise neither party can
keep, and it surfaces as a complaint rather than an alert.
