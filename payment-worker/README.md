# payment-worker — SKELETON

Posting queue to LMS, sweeper, reconciliation. **No ingress.**

Every duplicate-posting risk in the platform lives in this module, which is why they are
concentrated here rather than spread across services.

## Components
| Component | Trigger | Job |
|---|---|---|
| Posting consumer | Kafka `posting.requested` | claim, post to LMS, transition |
| Posting sweeper | every 5 min | rows older than 10 min that Kafka missed |
| Reconciliation | `NEXT_CHECK_AT` due | gateway status API by `tvstxnid` |

## Non-negotiable
**Claim, never select.**
```sql
SELECT ID FROM PAYMENT_POSTING_QUEUE
 WHERE STATUS='PENDING' AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
 ORDER BY NEXT_ATTEMPT_AT
 FETCH FIRST :batch ROWS ONLY
   FOR UPDATE SKIP LOCKED
```
Workers on different servers claim disjoint rows. No lock service, no leader election; a
server dying mid-cycle unlocks its rows when the transaction ends.

**Conditional transitions.** `UPDATE ... WHERE REFERENCE_ID=? AND STATUS='CLAIMED'`. Zero
rows means another worker won. Not an error.

**One shared `group.id=payment-posting` across every worker.** A hostname or UUID in the
group id gives each instance its own group and every instance every message — Kafka
becomes a duplicate multiplier. Check this first. Ack after the database commit, never
before.

**Rate limiting is the drain rate**: `batch size ÷ cycle interval`. Across N servers,
either divide the target N ways or share a Redis token bucket at `rl:lms-posting`. Back
off exponentially on an LMS rate-limit rejection — retrying at full speed into a throttle
turns it into an outage.

## Calling the LMS

Do not write an HTTP client here. The posting chain — status check, agreement fetch, OTS
amount, post transaction — is already on `LmsClient` in `payment-shared`, implemented by
`LmsApiClient` over the one shared transport. Inject `LmsClient`.

Three of those methods are marked SEAM: their request bodies are placeholders until the
LMS team confirms the contracts. Their sequencing is not a placeholder — it is settled in
POSTING_ARCHITECTURE.md and the method names follow it.

Every call is already bounded by the `lms` bulkhead and breaker and already writes to
`LMS_API_LOG` with the correlation id, the transaction reference and the attempt number.
Pass `txnReference` and `attemptNo` on every posting-phase call or the audit row cannot be
tied back to the attempt that made it.

## Reconciliation schedule
Escalating, not fixed: +2, +5, +10, +20, +30, +45 minutes, then `UNRESOLVED` with an
alert. Same total window as a fixed 30-minute cycle, but the common case resolves in
minutes and it makes fewer vendor calls. **The terminal state is the point** — a bounded
retry window without one means payments silently vanish from tracking.

## Alerts
- oldest `PENDING` row age > 30 min
- `MANUAL_REVIEW` count > 0 — page; money collected with no loan record
- `UNRESOLVED` count > 0 — page
