# EMI Repayment Platform — Master Design Document

**Status:** target design · not yet built · nothing here has been compiled, load-tested or
penetration-tested
**Supersedes:** the five-microservice diagram and all earlier posting drafts

---

## Contents

1. [What the platform does](#1-what-the-platform-does)
2. [Technology decisions and their verdicts](#2-technology-decisions-and-their-verdicts)
3. [Services](#3-services)
4. [Deployment across six OCI servers](#4-deployment-across-six-oci-servers)
5. [Security model](#5-security-model)
6. [Flow A — borrower authentication](#6-flow-a--borrower-authentication)
7. [Flow B — browser payment](#7-flow-b--browser-payment)
8. [Flow C — partner direct posting](#8-flow-c--partner-direct-posting)
9. [Flow D — posting to the LMS](#9-flow-d--posting-to-the-lms)
10. [Data model](#10-data-model)
11. [Redis key layout](#11-redis-key-layout)
12. [Capacity and rate limiting](#12-capacity-and-rate-limiting)
13. [Duplicate prevention, consolidated](#13-duplicate-prevention-consolidated)
14. [Network](#14-network)
15. [Observability](#15-observability)
16. [Failure modes](#16-failure-modes)
17. [Built, skeleton, not started](#17-built-skeleton-not-started)
18. [Open questions by owner](#18-open-questions-by-owner)
19. [Document set](#19-document-set)

---

## 1. What the platform does

A borrower with a gold loan repays an EMI through a web or mobile portal. Payment
vendors also post repayments they have collected directly, through an authenticated API.
Every repayment must end up recorded in the LMS.

Three journeys:

| Journey | Who | Ends when |
|---|---|---|
| Borrower repayment | Browser or mobile | Vendor confirms payment and the LMS is updated |
| Vendor direct posting | Partner system, machine to machine | Challan issued and the LMS is updated |
| Reconciliation | Nobody — scheduled | Every unresolved transaction reaches a terminal state |

The platform replaces a single-server synchronous monolith. Almost every design decision
below is a consequence of moving off one server; §2 makes that explicit.

---

## 2. Technology decisions and their verdicts

| Technology | Why now | What breaks without it | Verdict |
|---|---|---|---|
| **Redis** | Shared session, atomic counters | Auth breaks across servers; the OTP cap stops binding | Mandatory |
| **Multiple servers** | One server is a single point of failure | Any restart is an outage | Mandatory |
| **Prometheus + Loki** | Log retention and incident reporting obligations | Cannot produce an incident timeline across servers | Mandatory |
| **Service split** | Blast radius and independent release | One vendor bug stops borrower login | Justified |
| **Docker** | Identical software on every server | Config drift between servers | Justified |
| **Kafka** | Immediate trigger for posting | Posting waits for the sweeper; nothing else | Optional, taken |

### The spine

The old platform kept sessions in JVM memory, counters in a field, and did LMS posting
inline. That worked because there was exactly one of everything.

With multiple servers: session state must be shared, counters must be atomic, posting
must be async, logs must be centralised, deployment must be repeatable. Nothing in the
right-hand column is a preference.

### Deliberately not adopted

- **Kubernetes and service mesh** — six fixed servers do not need an orchestrator
- **Kafka as the posting queue** — at-least-once with finite retention cannot be the
  record of a payment
- **Redis as the posting queue** — it acknowledges writes before replicating
- **Stateless JWT fallback when Redis is down** — cannot be revoked, and would run
  without replay protection or an attempt cap
- **A separate LMS service** — the shared library removes the duplication; a service adds
  a hop, not capacity
- **Virtual threads** — supported on JDK 25, deferred by choice
- **Redis failover work** — deferred by decision; see §16

---

## 3. Services

| Service | Owns | Ingress |
|---|---|---|
| `auth-service` | Handshake, ECDH, OTP, browser and partner tokens | Browser, partner |
| `fetch-service` | Agreement and customer details from the LMS | Browser, partner |
| `payment-service` | Browser payment lifecycle, vendor adapters, gateway callbacks | Browser, gateway |
| `posting-service` | Partner direct posting intake | Partner |
| `payment-worker` | Posting queue to LMS, sweeper, reconciliation | **None** |

### The rule that makes the split safe

**Only `auth-service` writes session state.** Every other service verifies a token
signature and reads one Redis key.

The token signs `sessionId` and `agreementNumber` rather than carrying them, so
verification recomputes the HMAC from the session the request arrived on and the
agreement being operated on. A token from another session, or presented against another
agreement, fails the signature — no lookup, no shared state.

The AES key cannot live in the token (that would transmit the key alongside the data it
protects), so it sits under its own Redis key. That single read is the entire coupling
between auth and everything downstream, and it belongs to `payment-shared`.

### `payment-shared`

Load-bearing infrastructure, not a convenience library: token verification, session key
access, ECDH and AES-GCM, the LMS client, the posting queue mapping, the ledgers.
Changing the session key layout or the token format is a coordinated release across every
service.

---

## 4. Deployment across six OCI servers

Existing split: 4 servers for vendor traffic, 2 for browser traffic. That is already the
workload separation the design wants.

| Group | Servers | Runs | Why |
|---|---|---|---|
| Vendor | 4 | `posting-service`, `payment-worker`, `auth-service` (partner tokens) | Posting volume gets its own capacity and failure domain |
| Browser | 2 | `auth-service`, `fetch-service`, `payment-service` | Latency-sensitive; a posting backlog cannot starve borrower login |

**One instance of each service per server.** Two copies on one host share a kernel, a disk
and a power supply — process count rises, failure domains do not. Capacity grows by
adding servers; container CPU limits use spare cores better than stacked processes.

Docker Compose per server. No orchestrator. Deployment is a runbook: drain at the load
balancer, stop, deploy, health-check, return. Workers can be stopped at any time —
claimed rows unlock when the transaction ends and the sweeper reclaims stale ones.

---

## 5. Security model

### Transport and payload

| Layer | Control |
|---|---|
| Transport | TLS 1.2 minimum, 1.3 preferred; HSTS with preload |
| Payload (browser) | AES-256-GCM envelope inside TLS, key from ephemeral ECDH P-256 |
| Payload (partner) | None — machine to machine over mTLS; an envelope on top adds nothing |
| Session cookie | `__Host-` prefixed, HttpOnly, Secure, SameSite=Strict |

The server's ECDH private key is discarded immediately after key derivation. It is never
stored in Redis and never written to Oracle, so no later database compromise decrypts
traffic captured today.

There is **no plaintext escape hatch**. The legacy implementation honoured an
`X-Accept-Plaintext` request header that its own CORS allow-list advertised.

### Session and token

| | Value |
|---|---|
| Session TTL | 5 minutes, sliding — idle time, not total |
| Token TTL | Same as the session |
| Tokens per session | Exactly one, reusable |

Single-use enforcement was replaced by idempotent payment initiation (`SETNX
payment:init:{sid}`), because a replayed token's only interesting use is starting a second
payment.

**Callbacks and status polling require no session.** A borrower may spend eight minutes at
a gateway and return to a dead session; both resolve by `tvstxnid` in Oracle instead.

### CSRF

Cookie plus an identical `X-Session-Id` header — a double-submit. A cross-site attacker
can make the browser attach the cookie but cannot read it to populate the header, and
cannot set a custom header without a preflight CORS refuses. The legacy backends disabled
CSRF and put nothing in its place.

### Anti-replay

Mandatory `X-Nonce` and `X-Timestamp` on every state-changing browser request, claimed in
Redis with `SETNX` inside a 60-second window. **The headers are required** — the legacy
filter validated them only when the client chose to send them, so a replayer simply
omitted them.

### OTP

| | |
|---|---|
| Length | 5 digits |
| TTL | 180 seconds |
| Max attempts | 3, then 15-minute lockout |
| Resend cooldown | 60 seconds |
| Per-agreement budget | 5 sends per hour |
| Storage | Peppered, salted HMAC — never the code |

**The attempt cap is load-bearing.** Three guesses against 100,000 values is roughly 1 in
33,000 per session, which is what makes a 5-digit code acceptable. Raising the cap,
removing the lockout, or changing the counter reset are security changes.

The pepper — not the hash cost — is the control. A 5-digit space is exhaustible instantly
by anyone who has it, and it lives in the secret store, not Redis.

Counting uses Redis `INCR`. The legacy code read a counter off a session object,
incremented it in the JVM and wrote it back, so parallel requests all read the same value
and the cap never bound.

### Secrets

The application refuses to start without `PAYMENT_TOKEN_HMAC_SECRET`, `PAYMENT_OTP_PEPPER`
and `ACTUATOR_PASSWORD`, and rejects anything under 32 bytes or containing a placeholder
string. No fallback default exists anywhere.

### Known risk carried from the existing integration

The SMS gateway takes the message as a GET query parameter, so the OTP and mobile number
land in the access logs of every hop — egress proxy, provider load balancer, their web
server. Every other part of the platform is built so the OTP never reaches a log; this
one hop defeats it and cannot be fixed from our side. Needs a POST endpoint, or a named
risk owner and written retention terms.

---

## 6. Flow A — borrower authentication

```
POST /api/v1/auth/handshake        ECDH, session, __Host- cookie      [unencrypted]
POST /api/v1/auth/verify-agreement LMS lookup, OTP dispatched          [encrypted]
POST /api/v1/auth/resend-otp       cooldown + send budget apply        [encrypted]
POST /api/v1/auth/verify-otp       returns the payment token           [encrypted]
```

Every call after the handshake requires, together: the session cookie, an identical
`X-Session-Id` header, `X-Nonce`, `X-Timestamp`, and an encrypted body. Missing any one is
a rejection, not a downgrade.

### Inside `verify-otp`

Increment attempts (`INCR`, before the comparison, so a cancelled connection still costs
an attempt) → fetch the record → recompute the peppered hash → constant-time compare →
`DEL` the OTP key and proceed **only if the delete returns true**.

That last step decides the winner of a race. Two concurrent requests with the same correct
code can both pass the cap and both match the hash; `DEL` is atomic and returns true to
exactly one caller. Previously both issued a token, the second save overwrote the first,
and the borrower held a token that had silently stopped working.

### Agreement lookup

The LMS returns HTTP 200 for everything; success is `status == "Success"` in the body.
"No such agreement" and "lookup failed" are therefore indistinguishable, which is
convenient — the response cannot confirm whether an agreement number is real.

If the LMS returns more than one distinct mobile number across `success[]`, the lookup is
**rejected** rather than using the first. Sending to `success[0]` when numbers differ
delivers one borrower's code to another person's phone.

---

## 7. Flow B — browser payment

```
GET  /api/v1/customer/details         repayment context          fetch-service
POST /api/v1/payment/initiate         SETNX payment:init:{sid}   payment-service
GET  /api/v1/payment/vendors          backend-computed list      payment-service
POST /api/v1/payment/select-vendor    creates tvstxnid           payment-service
     → borrower redirected to the gateway
POST /api/v1/payment/webhook/{vendor} gateway callback           payment-service
GET  /api/v1/payment/status/{tvstxnid} polling, no session       payment-service
```

### Vendor eligibility

The backend computes the eligible vendor list per transaction. The frontend renders
exactly what it is given and never infers availability from static configuration.

`tvstxnid` is backend-generated, its prefix determined by the selected vendor, unique per
attempt, and persisted before the borrower is redirected.

### The callback

Arrives from the vendor, not the borrower. No session, no token.

Verify the vendor signature with a constant-time compare → resolve by `tvstxnid` in Oracle
→ transition conditionally (`WHERE STATUS = 'INITIATED'`) → write the posting queue row →
publish to Kafka → return 200.

Constraints:

- Must finish well inside the gateway's timeout, typically ~60 seconds. Touches only
  Oracle and Kafka, **never the LMS**.
- Zero rows updated means already handled — return 200 anyway, or the gateway retries
  harder.
- The callback may arrive long after the session expired. That is normal.

**This is the single highest-value control in the platform.** The legacy implementation
had a `verifyGatewaySignature` whose entire body was `return true;`, and the caller ignored
the result — a forgeable payment success reachable by anyone who could guess a transaction
id.

### Reconciliation

When a callback is lost entirely, the gateway's status API is queried by `tvstxnid` on an
escalating schedule: +2, +5, +10, +20, +30, +45 minutes, then `UNRESOLVED` with an alert.

Escalating rather than a fixed 30-minute cycle: the common case resolves in minutes, and
it makes fewer vendor calls because most resolve on the first check. **The terminal state
is the point** — a bounded retry window without one means payments silently vanish from
tracking.

---

## 8. Flow C — partner direct posting

```
POST /api/v1/partner/token            API key + secret → bearer     auth-service
POST /api/v1/partner/payment/posting  callback intake               posting-service
GET  /api/v1/partner/posting/{ref}    status lookup                 posting-service
```

Partner secrets are stored as bcrypt hashes in configuration, so a config dump does not
yield a usable credential. Verification always runs the encoder, even for an unknown
partner code, so response timing does not reveal which codes exist.

Partner tokens reuse the platform token format with `PARTNER:{code}` in the session-id
position and no agreement bound, so `posting-service` verifies them with the same code
path the browser uses.

### The intake transaction

```
authenticate → validate → check idempotency key
  → generate challan (Oracle sequence) → INSERT queue row → COMMIT
  → respond SUCCESS + challanNumber
```

All one transaction. Never generate the challan, respond, then write — a crash in that
gap leaves a partner holding a challan for a payment with no record, unfindable by
reconciliation because it does not exist.

The same idempotency key always returns the **same challan number**. That is what
`UNIQUE (PARTNER_CODE, PARTNER_IDEM_KEY)` is for: one line of DDL the whole integration
rests on.

### What the contract must say in writing

The challan confirms **the payment has been accepted and recorded by us**, not that the
loan account is updated. Those are minutes apart normally and hours during an LMS outage.
If the partner's customer-facing message says "loan updated", that is a promise neither
party can keep.

---

## 9. Flow D — posting to the LMS

The response has already gone back. Everything here is asynchronous.

```
FIRST ATTEMPT                 RETRY
─────────────                 ─────
1. Agreement fetch            1. Status check      ← the only difference
2. OTS amount details         2. Agreement fetch
3. Post transaction           3. OTS amount details
                              4. Post transaction
```

**The status check runs only on retry.** A first attempt cannot be a duplicate, so the
call is skipped — roughly 25% fewer LMS calls in the normal path. On retry it is also the
resume mechanism: if a post landed before the last attempt was cut off, the check finds
it.

That is why there is no per-step state table. An earlier draft had one; the status check
does the job.

### Status model

| Status | Meaning | Next |
|---|---|---|
| `PENDING` | Waiting for an executor | Claimed |
| `IN_FLIGHT` | An executor is working it | `POSTED`, `PENDING`, `REJECTED`, or stranded |
| `POSTED` | Post succeeded | Terminal |
| `REJECTED` | **Business rejection** — amounts do not reconcile | Terminal, alerts |
| `NOT_APPLICABLE` | Callback status was FAILED — nothing to post | Terminal |
| `MANUAL_REVIEW` | Retries exhausted | Terminal, **pages** |

`REJECTED` is separate from `MANUAL_REVIEW` on purpose. A technical failure is worth
retrying; a business rejection is not — the same call in two minutes returns the same
answer. Retrying it burns rate permits real work needs and buries the one row a human must
look at.

### `IN_FLIGHT` and the 60-second floor

A row marked `IN_FLIGHT` is being worked by an executor on some server. The sweeper
reclaims only rows whose `CLAIMED_AT` is older than **60 seconds** — beyond the LMS's own
30-second timeout plus margin. Reclaim earlier and a sweeper on one server takes a row an
executor on another is still posting.

### Retry schedule

| Attempt | When | Calls |
|---|---|---|
| 1 | immediately after commit | 3 |
| 2 | +2 min | 4 |
| 3 | +5 min | 4 |
| 4 | +15 min | 4 |
| 5 | +30 min | 4 |
| 6 | +60 min | 4 |
| — | — | → `MANUAL_REVIEW`, pages |

`REJECTED` exits this schedule immediately.

---

## 10. Data model

| Table | Purpose | Write pattern |
|---|---|---|
| `PAYMENT_POSTING_QUEUE` | Current state, one row per transaction | Insert then update |
| `LMS_API_LOG` | Every LMS call, full bodies | **Insert only**, async |
| `PAYMENT_ATTEMPT` | Browser payment attempts, `tvstxnid` | Insert then update |
| `PAYMENT_AUDIT_EVENTS` | Authentication and authorisation events | **Insert only**, sync |

### Two records, two purposes

`PAYMENT_POSTING_QUEUE` answers "what still needs posting". `LMS_API_LOG` answers "what
exactly did we send, and what came back". Merging them loses both answers.

The log write is asynchronous and **must never fail the posting**. Failing a payment
because an audit insert failed is the worse outcome.

### Grants and partitioning

Ledger tables: `GRANT INSERT, SELECT` only. The repository interfaces expose no update or
delete, but enforce it at the grant too, so a compromised service cannot rewrite its own
history.

All four tables are interval-partitioned monthly. `LMS_API_LOG` carries CLOBs and up to
four rows per attempt, making it the largest table in the schema; without partitions a
retention purge is a very expensive `DELETE` rather than a `DROP PARTITION`.

### Key constraints

| Constraint | Stops |
|---|---|
| `UQ_PPQ_PARTNER (PARTNER_CODE, PARTNER_IDEM_KEY)` | A partner retry creating a second payment |
| `UQ_PPQ_CHALLAN` | Two payments sharing a challan |
| `UQ_PA_TVSTXNID` | Two attempts sharing a transaction id |

Challan numbers come from an Oracle sequence, never a timestamp plus random suffix — a
collision attaches one payment's confirmation to another borrower's record.

### Claim query

```sql
SELECT ID FROM PAYMENT_POSTING_QUEUE
 WHERE STATUS = 'PENDING' AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
 ORDER BY NEXT_ATTEMPT_AT
 FETCH FIRST :batch ROWS ONLY
   FOR UPDATE SKIP LOCKED
```

Workers on different servers claim disjoint rows. No lock service, no leader election; a
server dying mid-cycle unlocks its rows when the transaction ends.

### Every completion is conditional

```sql
UPDATE ... SET STATUS = 'POSTED' WHERE TXN_REFERENCE = :txn AND STATUS = 'IN_FLIGHT'
```

Zero rows affected means another executor already finished it. Not an error.

---

## 11. Redis key layout

```
session:state:{sid}        hash: data + version     auth-service ONLY
session:key:{sid}          AES key hex              auth writes · fetch/payment read
claim:token-issue:{sid}    SETNX                    auth
payment:init:{sid}         SETNX                    payment-service
nonce:{n}                  SETNX, 65s TTL           all request-serving services
otp:code|attempts|cooldown|sends|lock:{...}         auth
rl:lms-posting             token bucket             payment-worker
upstream:token:{lms|sms}   cached bearer            all
```

`session:state` and `session:key` share a TTL and are deleted together, so a downstream
key read is a reliable liveness check without any state access.

Session writes are **compare-and-swap**: a Lua script writes only if the version still
matches what the caller loaded, and extends the TTL on both keys in the same atomic
operation. A mismatch throws 409 and is **not retried** — re-applying a transition against
state the caller never read is how a session ends up bound to two agreements.

---

## 12. Capacity and rate limiting

| | |
|---|---|
| Peak postings today | 300/min |
| Configured ceiling | 600/min |
| **LMS calls at ceiling** | **1,800/min first attempt · 2,400/min with retries** |

That last row is the figure to agree with the LMS team, in their unit. A four-fold
misunderstanding is how an agreed limit becomes an outage.

One permit per transaction, taken before the first LMS call — not per call — so a retry's
extra status check does not consume two. The bucket is shared in Redis, so the ceiling
holds across all servers; a per-server limit would mean `servers × rate` arriving at the
LMS.

No permit means the row stays `PENDING`. Under a spike, posting slows rather than flooding
the LMS.

### The other ceiling

Fetch-side LMS calls are bounded by a bulkhead (`max-concurrent-calls`, fail fast, no
queueing) so a slow LMS cannot consume every worker thread and starve `verify-otp`. The
bulkhead and the circuit breaker are not redundant: the breaker reacts to sustained
failure once it has samples; the bulkhead bounds the first thirty seconds, which with a
30-second timeout is when the damage happens.

`maxReplicas × bulkhead_limit` must stay inside whatever concurrency the LMS accepts.
Horizontal scaling multiplies upstream load rather than relieving it.

---

## 13. Duplicate prevention, consolidated

| # | Risk | Control | Where |
|---|---|---|---|
| 1 | Partner retries the callback | `UNIQUE (partner, idem key)` returns the original challan | posting-service |
| 2 | Gateway retries the callback | Conditional update on `INITIATED`; always return 200 | payment-service |
| 3 | Two executors claim one row | `FOR UPDATE SKIP LOCKED` | worker |
| 4 | Sweeper reclaims a live row | `IN_FLIGHT` + 60-second floor | worker |
| 5 | Retry after an LMS timeout | Status check on every retry | worker |
| 6 | Status check misses a just-landed post | Unique client reference on the post call | worker |
| 7 | Kafka redelivery | Conditional update; zero rows means done, ack anyway | worker |
| 8 | Callback races reconciliation | Same conditional update on `INITIATED` | payment-service |
| 9 | Two concurrent OTP verifications | `DEL` returns true to exactly one caller | auth-service |
| 10 | Double-click on payment initiation | `SETNX payment:init:{sid}` | payment-service |

Controls 5 and 6 are layered deliberately: the status check catches the common case
cheaply, control 6 covers the window between the check returning "not posted" and the post
landing.

**Every worker shares `group.id=payment-posting`.** A hostname or UUID in the group id
gives each instance its own group and every instance every message — Kafka becomes a
duplicate multiplier. Check this first. Ack after the database commit, never before.

---

## 14. Network

Two ingress paths, three trust populations.

| Path | Hostname | Auth | Notes |
|---|---|---|---|
| Borrower + partner | `payment.tvscredit.com` | Session cookie + header, or partner bearer | Split by path prefix at the gateway |
| Gateway callbacks | `hooks.tvscredit.com` | Vendor signature, source IPs allowlisted by the network team | Separate WAF policy |

Shared L7 load balancer pool by decision. The accepted risk: a Layer 7 flood on borrower
traffic competes with vendor callbacks for the same connection pool. Reconciliation
recovers a dropped callback within minutes.

### Zones

| Zone | Internet inbound | Internet outbound |
|---|---|---|
| Edge — API gateway | yes | n/a |
| Application — private subnets | never directly | only via the egress allowlist |
| Data — isolated subnets | never | **never — no default route** |

All outbound traffic leaves through NAT with **fixed, documented IPs**, because the LMS
and every vendor allowlist them. A NAT replacement that changes the address breaks
payments silently, at the vendor end.

Data connections are encrypted in transit: Redis TLS, Oracle TCPS, Kafka SASL_SSL. "It's
on a private subnet" is not encryption, and the session AES keys travel these links.

Metrics bind to the host interface on port 8081 and require authentication even there.
Network position alone is not authentication — in most environments "on the internal
network" means every other host.

All zones in India regions only.

---

## 15. Observability

### Alerts that page

| Signal | Why |
|---|---|
| `MANUAL_REVIEW` count > 0 | Money collected, no loan record |
| `UNRESOLVED` payment count > 0 | Payment status never determined |
| `AUDIT WRITE FAILED` | A control failure, not an error |

### Alerts that warn

| Signal | Threshold |
|---|---|
| Oldest `PENDING` posting row age | > 30 min |
| `REJECTED` count | > 0 — needs an operator, not a retry |
| Stranded `IN_FLIGHT` reclaims | > 0 means servers dying mid-posting |
| Rate permits available | Approaching zero |
| LMS bulkhead rejections | Upstream saturation |
| Webhook signature failures | Attack signal |
| `SESSION_CONFLICT` rate | Real concurrency, or a UI double-submit bug |
| Unique-constraint violations | A duplicate was attempted and blocked |

### Correlation

One correlation id per request, in the MDC, on every audit row, every LMS call row, and
the response header. A client-supplied value is accepted only if it parses as a UUID, so
the field cannot inject content into a log line.

In Loki, `correlationId` is a **field, not a label** — a per-request label is the standard
way to destroy a Loki install.

---

## 16. Failure modes

| Failure | Behaviour | Borrower impact |
|---|---|---|
| **Redis down** | 503 across request-serving services | **Total outage, by design** |
| Oracle down | 503; workers idle; no challan can be issued | Total outage |
| LMS fetch down | Bulkhead + breaker, controlled error | Cannot start new journeys |
| LMS posting down | Queue grows, backoff, alert on age | None — challan already issued |
| SMS gateway down | OTP dispatch fails, borrower told to retry | Cannot authenticate |
| Kafka down | Worker finds rows on its next poll | Posting up to 10 min later |
| One server dies | LB removes it; claimed rows unlock; sweeper reclaims | None |
| `payment-service` down | Callbacks fail; gateway retries; reconciliation catches the rest | New payments blocked |
| Amounts do not reconcile | `REJECTED`, alert, no retry | None |
| Log write fails | Posting continues, alert raised | None |

### Redis, stated plainly

Redis availability equals service availability. This is deliberate: degrading to per-node
state silently disables replay protection, attempt counting and the compare-and-swap
across servers, which is worse than a clean 503.

Failover and replication work has been **deferred by decision**. The runbook line that
must exist: *a Redis outage is a total outage by design; the correct response is to
restore Redis, not to degrade the application.*

---

## 17. Built, skeleton, not started

| Component | State |
|---|---|
| `payment-shared` | Built — token, session key access, crypto, LMS client, ledgers |
| `auth-service` | **Built through OTP verification**, plus partner token issuance |
| `fetch-service` | Skeleton — application class, config, README |
| `payment-service` | Skeleton |
| `posting-service` | Skeleton |
| `payment-worker` | Skeleton |
| Angular client | Built through OTP verification |

### Two methods still need your code

`SmsTokenProvider.requestToken()` and `LmsTokenProvider.requestToken()` — the request
shapes for `getSmsAccessToken()` and `getEXApiToken(fiid)` were not available. Each is
isolated so the surrounding logic (Redis caching, early expiry, interaction recording,
circuit breaker) does not change.

### Build order from here

1. `PAYMENT_POSTING_QUEUE` and `LMS_API_LOG` tables, challan sequence, unique constraints
2. Claim-and-transition primitives, with concurrency tests against real Oracle
3. `payment-worker` — posting executor, sweeper
4. `posting-service` — intake, challan, partner ingress
5. `payment-service` — lifecycle, vendor adapters, **callback signature verification**
6. `fetch-service`
7. Reconciliation
8. Rate limiter
9. Load test: N workers against one queue, callback storms, LMS degradation

Steps 1 and 2 are the architecture. Everything after is plumbing around them.

---

## 18. Open questions by owner

**Nine of twelve are LMS questions.** The largest risk to this project is a single
external dependency, not the architecture.

### LMS team

1. Is the post API **idempotent on a client reference** — reject as duplicate, or return
   the original? Decides whether duplicate control 6 closes the timeout window or merely
   narrows it.
2. Does the **status-check API cover a whole transaction**, or one call within it?
3. What is the **concurrent request ceiling** you will accept from us?
4. Committed **p95 and p99 latency**, and what happens at month-end?
5. Is the agreed ceiling in **transactions or API calls**? 600 postings/min is
   1,800–2,400 calls/min.
6. Request shape for `getEXApiToken(fiid)`.
7. Can `success[]` contain **different mobile numbers** for one customer?
8. What counts as an **amount mismatch** — exact, or a tolerance?
9. Do **FAILED callbacks** need posting to the LMS, or stop at `NOT_APPLICABLE`?

### SMS provider

10. Is a **POST endpoint** available? The current GET puts the OTP in a query string.
    Also: request shape for `getSmsAccessToken()`.

### Vendors and network

11. Callback **source IP ranges** for the allowlist; each vendor's callback **retry
    policy** and status API contract.

### Internal

12. **Log retention** period and location; challan number format convention; whether the
    displayed payment allocation is a promise to the borrower (§9 — rules are evaluated
    at posting time, so a delayed posting may allocate differently than displayed).

---

## 19. Document set

| Document | Covers |
|---|---|
| This document | Everything, consolidated |
| `TARGET_ARCHITECTURE_V2.md` | Service boundaries, token model, deployment |
| `POSTING_ARCHITECTURE.md` | Callback to LMS in detail, schema, retry |
| `NETWORK_ARCHITECTURE.md` | Trust boundaries, for architecture review |
| `NETWORK_BUILD_SPEC.md` | CIDRs, security groups, WAF rules, NetworkPolicy |
| `DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` | Docker, images, probes, scaling |
| `CAPACITY_AND_PERFORMANCE.md` | Sizing model, bottleneck analysis, load test plan |
| `target-architecture-v2.svg/png` | Platform diagram |
| `posting-architecture.svg/png` | Posting flow diagram |

Mermaid diagrams need the `bierner.markdown-mermaid` extension to render in VS Code.
GitHub, GitLab and Confluence render them natively.
