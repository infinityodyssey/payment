# EMI Repayment Platform — Master Handoff Document

**Purpose:** everything needed to continue this project from a cold start. Design,
decisions, rejected alternatives, code state, conventions and open questions.

**Status:** target design · auth-service built through OTP verification · nothing
compiled, load-tested or penetration-tested.

**If you read one section:** §3 (rejected decisions). Everything else can be re-derived;
that section cannot, and it is what stops the same arguments being relitigated.

---

## Contents

| § | Section |
|---|---|
| 0 | [Briefing a new assistant](#0-briefing-a-new-assistant) |
| 1 | [What we are building](#1-what-we-are-building) |
| 2 | [Decisions taken, and why](#2-decisions-taken-and-why) |
| 3 | [Decisions rejected, and why](#3-decisions-rejected-and-why) |
| 4 | [Services and the rule that makes the split safe](#4-services-and-the-rule-that-makes-the-split-safe) |
| 5 | [Deployment](#5-deployment) |
| 6 | [Security model](#6-security-model) |
| 7 | [Flow A — authentication](#7-flow-a--authentication) |
| 8 | [Flow B — browser payment](#8-flow-b--browser-payment) |
| 9 | [Flow C — partner posting](#9-flow-c--partner-posting) |
| 10 | [Flow D — posting to the LMS](#10-flow-d--posting-to-the-lms) |
| 11 | [Data model](#11-data-model) |
| 12 | [Redis key layout](#12-redis-key-layout) |
| 13 | [Capacity and rate limiting](#13-capacity-and-rate-limiting) |
| 14 | [Duplicate prevention](#14-duplicate-prevention) |
| 15 | [Network](#15-network) |
| 16 | [Observability](#16-observability) |
| 17 | [Failure modes](#17-failure-modes) |
| 18 | [Code state and conventions](#18-code-state-and-conventions) |
| 19 | [Defects found in the legacy system](#19-defects-found-in-the-legacy-system) |
| 20 | [Open questions](#20-open-questions) |
| 21 | [Build order](#21-build-order) |

---

## 0. Briefing a new assistant

Paste this, attach this document, and continue.

> I am building an EMI repayment platform for a vehicle and gold finance NBFC. The
> attached document is the complete design, decision log and code state. Read §2 and §3
> before proposing anything — several obvious-looking suggestions have already been
> considered and rejected for specific reasons.
>
> Constraints that are settled and not open for redesign: Spring Boot 4 / Java 25 with
> virtual threads off; five services plus one worker; 6 OCI servers running Docker
> Compose, no Kubernetes; Redis fails closed with no fallback; Oracle is the record and
> Kafka is only a trigger; only auth-service writes session state; only payment-worker
> calls the LMS posting API.
>
> I want you to push back when I am wrong rather than agree. Tell me the uncomfortable
> thing first.

---

## 1. What we are building

A borrower repays a gold-loan EMI through a web or mobile portal. Payment vendors also
post repayments they collected directly, through an authenticated API. Every repayment
must end up recorded in the LMS.

| Journey | Who | Ends when |
|---|---|---|
| Borrower repayment | Browser or mobile | Vendor confirms and the LMS is updated |
| Vendor direct posting | Partner system, machine to machine | Challan issued and the LMS is updated |
| Reconciliation | Scheduled | Every unresolved transaction reaches a terminal state |

Replaces a single-server synchronous monolith. Almost every decision below follows from
moving off one server.

---

## 2. Decisions taken, and why

| # | Decision | Reason | What it prevents |
|---|---|---|---|
| 1 | **Redis for session state** | Three servers cannot share JVM memory | Auth breaking across servers; the OTP cap silently tripling |
| 2 | **Redis fails closed (503)** | Degrading disables the controls that stop fraud | A local fallback map that silently kills replay protection |
| 3 | **Multiple servers** | One server is a single point of failure | Every restart being an outage |
| 4 | **One instance per service per server** | Two copies share a kernel, disk and power supply | Process count rising without failure domains |
| 5 | **Five services + worker** | Blast radius and release independence, not throughput | A vendor bug stopping borrower login |
| 6 | **Only auth-service writes session state** | Makes the service split safe | A distributed monolith mutating one state machine over a network |
| 7 | **Token signs session id + agreement** | Binding is cryptographic, not a lookup | Downstream services needing session-state access |
| 8 | **One reusable token per session** | Simplicity; 5-minute window | Supersession bookkeeping |
| 9 | **Idempotent payment initiation** | Replaces single-use token enforcement | A replayed token starting a second payment |
| 10 | **Oracle is the posting queue** | A payment needs a queryable durable row | "Has this posted?" becoming a question about consumer offsets |
| 11 | **Kafka is a trigger only** | At-least-once, finite retention | Kafka becoming the system of record |
| 12 | **Only payment-worker posts to LMS** | Two posting components make duplicates unfixable | Racing posting paths |
| 13 | **`SKIP LOCKED` claiming** | Workers pull disjoint rows | A lock service, leader election, and stale locks |
| 14 | **Compare-and-swap session writes** | Redis Lua is atomic | Lost updates: two tokens where the first silently dies |
| 15 | **Status check only on retry** | A first attempt cannot be a duplicate | ~25% of LMS calls |
| 16 | **Session-scoped LMS snapshot** | One fetch serves OTP and the details screen | A second LMS call per journey |
| 17 | **5-digit OTP** | Acceptable *because* of the 3-attempt cap | — |
| 18 | **Docker, no Kubernetes** | Six fixed servers need consistency, not orchestration | Config drift; unnecessary platform |
| 19 | **Virtual threads off** | Matches existing production model | — |
| 20 | **Prometheus + Loki** | Log retention and 6-hour incident reporting obligations | Being unable to build an incident timeline |
| 21 | **Escalating reconciliation** | Common case resolves in minutes | Fixed 30-min cycles and more vendor calls |
| 22 | **Terminal states that alert** | `MANUAL_REVIEW`, `REJECTED`, `UNRESOLVED` | Payments silently vanishing from tracking |
| 23 | **Redis failover work deferred** | Explicit decision | — |

---

## 3. Decisions rejected, and why

**Read this before proposing anything.** Each of these looks reasonable and has already
been argued.

### Kafka as the posting queue — rejected

At-least-once with finite retention. "Has this payment been posted?" would become a
question about consumer offsets: unqueryable, unjoinable to a payment record,
unshowable to an auditor. A posting needs a row with a unique constraint, a status, an
attempt count and a last error that survives six months to a dispute.

*Objections raised and answered:* lock contention does not arise because `SKIP LOCKED`
means workers never wait; index bloat is a PostgreSQL behaviour, not Oracle; both tables
are interval-partitioned so purging is a partition drop.

### Redis as the posting queue — rejected

Redis acknowledges writes before replicating. A failover loses the last second of
writes. For a session that means re-login; for a payment posting it means a vendor
holding a challan for money you have no record of.

### Stateless JWT fallback when Redis is down — rejected

Cannot be revoked. The session AES key cannot go in a token — that transmits the key
alongside the data it protects. And Redis holds more than sessions: nonces, OTP attempt
counters, the CAS version. "Fall back to JWTs" means running without replay protection
and without a working attempt cap. This is the legacy `memoryFallback` defect in a new
costume.

### Local fallback map when Redis is down — rejected

Same defect, explicitly. Per-node state does not de-duplicate nonces or count attempts
across a cluster. A clean 503 is better than silently serving without controls.

### Merging auth into payment-service — rejected

They would share session writes. Splitting them either puts a network hop inside a state
machine or has two services sharing a secret and a schema. The self-contained token
solved this instead.

### A separate LMS service — rejected

The shared library already removes the duplication. A service adds a hop, not capacity,
and creates a new failure mode: caller times out waiting on lms-service which is timing
out waiting on the LMS. Would become right only with real integration complexity —
response caching with invalidation rules, multi-product routing, request coalescing.

### Kubernetes and service mesh — rejected

Six fixed servers do not need an orchestrator. Revisit if the server count grows
materially.

### Separate load balancer pools for webhooks — considered, then dropped

Was recommended; dropped because the network team allowlists webhook sources. **Accepted
risk:** a Layer 7 flood on borrower traffic competes with vendor callbacks for the same
connection pool. Reconciliation recovers a dropped callback within minutes.

### Per-step posting state table — built, then removed

An earlier draft modelled each LMS call as a row so a retry could resume mid-sequence.
Unnecessary: the retry's status check is the resume mechanism.

### Inline 3-second LMS wait then queue — removed

The response returns immediately after commit. Nothing waits on an LMS call.

### Caching LMS data for OTP dispatch — rejected

A stale mobile number sends a borrower's code to a phone they may no longer control. The
session snapshot is written *at* OTP dispatch and read *later* for display, which is the
safe direction.

### Using the snapshot to decide the payment amount — rejected

If the borrower pays through another channel between OTP and the payment screen, cached
dues are stale. Display from the snapshot; validate the amount against a fresh call at
initiation.

### Consolidating all rate limiting at the API gateway — rejected

The gateway cannot enforce the OTP attempt cap or the per-agreement send budget: both
key on the agreement number, which is inside an AES-GCM envelope it cannot read. An
attacker with a proxy pool defeats per-IP limits alone.

### Virtual threads on Java 21 — rejected, then moot

Virtual threads pin their carrier inside `synchronized` blocks, which Lettuce and
BouncyCastle both use. JEP 491 fixed this in JDK 24. Now on JDK 25 they are safe but
switched off by choice. **If they are ever enabled, the thread pool stops bounding
upstream concurrency and the bulkheads become the only backpressure.**

---

## 4. Services and the rule that makes the split safe

| Service | Owns | Ingress |
|---|---|---|
| `auth-service` | Handshake, ECDH, OTP, browser and partner tokens | Browser, partner |
| `fetch-service` | Agreement and customer details | Browser, partner |
| `payment-service` | Payment lifecycle, vendor adapters, gateway callbacks | Browser, gateway |
| `posting-service` | Partner direct posting intake | Partner |
| `payment-worker` | Posting queue → LMS, sweeper, reconciliation | **None** |

**Only `auth-service` writes session state.** Everything else verifies a token signature
and reads one Redis key.

The token signs `sessionId` and `agreementNumber` rather than carrying them, so
verification recomputes the HMAC from the session the request arrived on and the
agreement being operated on. A token from another session, or against another agreement,
fails the signature — no lookup, no shared state.

The AES key cannot live in the token (that transmits the key alongside the data it
protects), so it sits under its own Redis key. That single read is the entire coupling
between auth and everything downstream.

`payment-shared` is load-bearing infrastructure, not a convenience library. Changing the
session key layout or the token format is a coordinated release across every service.

---

## 5. Deployment

Six OCI servers, split 4 vendor / 2 browser — an existing split that happens to be the
workload separation the design wants.

| Group | Servers | Runs |
|---|---|---|
| Vendor | 4 | `posting-service`, `payment-worker`, `auth-service` (partner tokens) |
| Browser | 2 | `auth-service`, `fetch-service`, `payment-service` |

One instance of each service per server. Docker Compose, no orchestrator. Deployment is a
runbook: drain at the load balancer, stop, deploy, health-check, return. Workers can be
stopped at any time — claimed rows unlock when the transaction ends.

Java 25 LTS, Spring Boot 4.0, virtual threads off, Tomcat 200 threads.

---

## 6. Security model

### Transport and payload

| Layer | Control |
|---|---|
| Transport | TLS 1.2 min, 1.3 preferred; HSTS preload |
| Browser payload | AES-256-GCM envelope inside TLS, key from ephemeral ECDH P-256, AAD = session id |
| Partner payload | None — mTLS machine to machine |
| Session cookie | `__Host-` prefixed, HttpOnly, Secure, SameSite=Strict |

The server's ECDH private key is discarded immediately after derivation — never in
Redis, never in Oracle. No plaintext escape hatch exists.

### Session and token

| | |
|---|---|
| Session TTL | 5 minutes, sliding |
| Token TTL | Same as the session |
| Tokens per session | One, reusable |

**Callbacks and status polling require no session** — both resolve by `tvstxnid` in
Oracle. A borrower may spend eight minutes at a gateway and return to a dead session.

### CSRF

Cookie plus an identical `X-Session-Id` header. A cross-site attacker can make the
browser attach the cookie but cannot read it to populate the header.

### Anti-replay

Mandatory `X-Nonce` and `X-Timestamp`, claimed with `SETNX` in a 60-second window. **The
headers are required** — the legacy filter validated them only when the client chose to
send them.

### OTP

| | |
|---|---|
| Length | 5 digits |
| TTL | 180s |
| Max attempts | 3, then 15-minute lockout |
| Resend cooldown | 60s |
| Per-agreement budget | 5 sends/hour |
| Storage | Peppered, salted HMAC |

**The attempt cap is load-bearing.** Three guesses against 100,000 values is what makes a
5-digit code acceptable. Raising the cap, removing the lockout, or changing the counter
reset are security changes.

The pepper, not the hash cost, is the control — a 5-digit space is exhaustible instantly
by anyone who holds it, and it lives in the secret store, not Redis.

Counting uses Redis `INCR`. The legacy code read a counter off a session object,
incremented it in the JVM and wrote it back, so parallel requests all read the same value
and the cap never bound.

### Secrets

Startup refuses without `PAYMENT_TOKEN_HMAC_SECRET`, `PAYMENT_OTP_PEPPER`,
`ACTUATOR_PASSWORD`; rejects anything under 32 bytes or containing a placeholder string.
No fallback default anywhere.

### Carried risk

The SMS gateway takes the message as a GET query parameter, so the OTP and mobile number
reach access logs on hops outside your control. Cannot be fixed from our side. Needs a
POST endpoint, or a named owner and written retention terms.

---

## 7. Flow A — authentication

```
POST /api/v1/auth/handshake        ECDH, session, __Host- cookie   [unencrypted]
POST /api/v1/auth/verify-agreement LMS lookup, OTP sent, snapshot   [encrypted]
POST /api/v1/auth/resend-otp       zero LMS calls                   [encrypted]
POST /api/v1/auth/verify-otp       returns the payment token        [encrypted]
```

Every call after the handshake needs, together: cookie, identical `X-Session-Id` header,
`X-Nonce`, `X-Timestamp`, encrypted body.

### Inside `verify-otp`

`INCR` attempts **before** the comparison (a cancelled connection still costs an attempt)
→ fetch record → recompute peppered hash → constant-time compare → `DEL` and proceed
**only if the delete returns true**.

That last step elects the winner of a race. Two concurrent requests with the same correct
code both pass the cap and both match; `DEL` returns true to exactly one. Previously both
issued a token and the second save erased the first, leaving a token that silently
stopped working.

### Agreement lookup

The LMS returns HTTP 200 for everything; success is `status == "Success"` in the body. So
"no such agreement" and "lookup failed" are indistinguishable — the response cannot
confirm whether a number is real.

If `success[]` contains more than one **distinct mobile number**, the lookup is rejected
rather than using the first. Sending to `success[0]` when numbers differ delivers one
borrower's code to another person's phone.

### The session snapshot

`auth-service` stores the agreement-fetch response at `session:lms:{sid}` when it looks up
the mobile number. `fetch-service` reads it for the details screen instead of calling the
LMS again, and `resend-otp` makes **zero** LMS calls.

Scoped to one session, not one agreement. Two sessions for the same borrower hold
separate snapshots and may legitimately disagree. Deleted with the session.

---

## 8. Flow B — browser payment

```
GET  /api/v1/customer/details          from the snapshot, no LMS call
POST /api/v1/payment/initiate          SETNX payment:init + fresh amount validation
GET  /api/v1/payment/vendors           backend-computed eligibility
POST /api/v1/payment/select-vendor     generates tvstxnid, persisted before redirect
POST /api/v1/payment/webhook/{vendor}  gateway callback
GET  /api/v1/payment/status/{tvstxnid} polling, no session
```

### The callback

No session, no token. Verify the vendor signature (constant-time) → resolve by `tvstxnid`
→ conditional transition (`WHERE STATUS = 'INITIATED'`) → write the posting queue row →
publish to Kafka → return 200.

Must finish inside the gateway's timeout (~60s). Touches only Oracle and Kafka, **never
the LMS**. Zero rows updated means already handled — return 200 anyway.

**This is the single highest-value control in the platform.** The legacy
`verifyGatewaySignature` returned `true` and its result was ignored.

### Reconciliation

Gateway status API by `tvstxnid` at +2, +5, +10, +20, +30, +45 minutes, then `UNRESOLVED`
with an alert. The terminal state is the point — a bounded window without one means
payments silently vanish.

---

## 9. Flow C — partner posting

```
POST /api/v1/partner/token             API key + secret → bearer   auth-service
POST /api/v1/partner/payment/posting   intake                      posting-service
GET  /api/v1/partner/posting/{ref}     status lookup               posting-service
```

Partner secrets stored as bcrypt hashes. Verification always runs the encoder, even for
an unknown partner code, so timing does not reveal which codes exist.

Partner tokens reuse the platform format with `PARTNER:{code}` in the session-id position
and no agreement bound — `issuePartnerToken` / `verifyPartnerToken` enforce that
convention by signature rather than by discipline.

### The intake transaction

```
authenticate → validate → check idempotency key
  → generate challan (Oracle sequence) → INSERT → COMMIT → respond
```

All one transaction. Never generate, respond, then write — a crash in that gap leaves a
partner holding a challan for a payment with no record.

The same idempotency key always returns the **same challan**. That is what
`UNIQUE (PARTNER_CODE, PARTNER_IDEM_KEY)` is for.

**Contract to agree in writing:** the challan confirms *we have accepted and recorded the
payment*, not that the loan account is updated.

---

## 10. Flow D — posting to the LMS

The response has already gone back. Everything here is async.

```
FIRST ATTEMPT              RETRY
1. Agreement fetch         1. Status check      ← the only difference
2. OTS amount details      2. Agreement fetch
3. Post transaction        3. OTS amount details
                           4. Post transaction
```

Rules are evaluated at posting time, because a borrower can pay again before a retry and
change which rule applies. **Consequence for the business team:** a delayed posting may
allocate the money differently from what the borrower saw on screen.

### Status model

| Status | Meaning |
|---|---|
| `PENDING` | Waiting for an executor |
| `IN_FLIGHT` | An executor is working it |
| `POSTED` | Terminal |
| `REJECTED` | Business rejection — amounts do not reconcile. Terminal, alerts |
| `NOT_APPLICABLE` | Callback was FAILED — nothing to post |
| `MANUAL_REVIEW` | Retries exhausted. Terminal, **pages** |

`REJECTED` is separate from `MANUAL_REVIEW` because a technical failure is worth retrying
and a business rejection is not — the same call in two minutes returns the same answer.

### `IN_FLIGHT` and the 60-second floor

The sweeper reclaims only rows whose `CLAIMED_AT` is older than 60 seconds, beyond the
LMS's 30-second timeout. Reclaim earlier and a sweeper on one server takes a row an
executor on another is still posting.

### Retry

+2, +5, +15, +30, +60 minutes, then `MANUAL_REVIEW`. `REJECTED` exits immediately.

---

## 11. Data model

| Table | Purpose | Writes |
|---|---|---|
| `PAYMENT_POSTING_QUEUE` | Current state, one row per transaction | Insert then update |
| `LMS_API_LOG` | Every LMS/SMS call, full bodies | **Insert only**, async |
| `PAYMENT_ATTEMPT` | Browser attempts, `tvstxnid` | Insert then update |
| `PAYMENT_AUDIT_EVENTS` | Auth events | **Insert only**, sync |

The queue answers "what still needs posting". The log answers "what exactly did we send".
Merging them loses both answers. **The log write must never fail the posting.**

Ledger tables: `GRANT INSERT, SELECT` only. All tables interval-partitioned monthly —
`LMS_API_LOG` carries CLOBs and up to four rows per attempt, so a purge without
partitions is a very expensive `DELETE`.

Challan numbers come from an Oracle sequence, never a timestamp plus random suffix.

### The three queries that matter

```sql
-- claim
SELECT ID FROM PAYMENT_POSTING_QUEUE
 WHERE STATUS = 'PENDING' AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
 ORDER BY NEXT_ATTEMPT_AT
 FETCH FIRST :batch ROWS ONLY FOR UPDATE SKIP LOCKED;

-- stranded reclaim
UPDATE PAYMENT_POSTING_QUEUE SET STATUS='PENDING', NEXT_ATTEMPT_AT=SYSTIMESTAMP
 WHERE STATUS='IN_FLIGHT' AND CLAIMED_AT < SYSTIMESTAMP - INTERVAL '60' SECOND;

-- completion, always conditional
UPDATE PAYMENT_POSTING_QUEUE SET STATUS='POSTED', LMS_REFERENCE=:ref
 WHERE TXN_REFERENCE=:txn AND STATUS='IN_FLIGHT';
```

Zero rows affected is never an error — it means someone else already won.

---

## 12. Redis key layout

```
session:state:{sid}      hash: data + version    auth-service ONLY
session:key:{sid}        AES key hex             auth writes · others read
session:lms:{sid}        LMS snapshot            auth writes · fetch reads
claim:token-issue:{sid}  SETNX                   auth
payment:init:{sid}       SETNX                   payment-service
nonce:{n}                SETNX, 65s TTL          all request-serving
otp:code|attempts|cooldown|sends|lock:{...}      auth
rl:lms-posting           token bucket            payment-worker
upstream:token:{lms|sms} cached bearer           all
```

All three `session:*` keys share a TTL and are deleted together, so a downstream key read
is a reliable liveness check.

Session writes are **compare-and-swap** via Lua: write only if the version still matches,
extending both TTLs in the same atomic operation. A mismatch throws 409 and is **not
retried**.

---

## 13. Capacity and rate limiting

| | |
|---|---|
| Peak postings today | 300/min |
| Configured ceiling | 600/min |
| **LMS calls at ceiling** | **1,800/min first attempt · 2,400/min with retries** |

Agree the ceiling with the LMS team **in their unit**. A four-fold misunderstanding is
how an agreed limit becomes an outage.

One permit per transaction, before the first LMS call — not per call. Shared Redis bucket,
so the ceiling holds across servers.

Fetch-side calls are bounded by a bulkhead (fail fast, no queueing) so a slow LMS cannot
starve `verify-otp`. The bulkhead and the circuit breaker are not redundant: the breaker
needs a sample window, the bulkhead covers the first thirty seconds.

`servers × bulkhead_limit` must stay inside what the LMS accepts. Scaling out multiplies
upstream load.

---

## 14. Duplicate prevention

| # | Risk | Control |
|---|---|---|
| 1 | Partner retries the callback | `UNIQUE (partner, idem key)` returns the original challan |
| 2 | Gateway retries the callback | Conditional update on `INITIATED`; always return 200 |
| 3 | Two executors claim one row | `FOR UPDATE SKIP LOCKED` |
| 4 | Sweeper reclaims a live row | `IN_FLIGHT` + 60-second floor |
| 5 | Retry after an LMS timeout | Status check on every retry |
| 6 | Status check misses a just-landed post | Unique client reference on the post call |
| 7 | Kafka redelivery | Conditional update; zero rows means done, ack anyway |
| 8 | Callback races reconciliation | Same conditional update |
| 9 | Two concurrent OTP verifications | `DEL` returns true to exactly one |
| 10 | Double-click on initiation | `SETNX payment:init:{sid}` |

**Every worker shares `group.id=payment-posting`.** A hostname or UUID in the group id
gives each instance its own group and every instance every message — Kafka becomes a
duplicate multiplier. Check this first. Ack after the database commit, never before.

---

## 15. Network

Two ingress paths, three trust populations.

| Path | Hostname | Auth |
|---|---|---|
| Borrower + partner | `payment.tvscredit.com` | Cookie + header, or partner bearer |
| Gateway callbacks | `hooks.tvscredit.com` | Vendor signature, source IPs allowlisted |

Shared L7 load balancer pool by decision. Accepted risk noted in §3.

| Zone | Internet inbound | Internet outbound |
|---|---|---|
| Edge — API gateway | yes | n/a |
| Application — private | never directly | via the egress allowlist only |
| Data — isolated | never | **never — no default route** |

Outbound leaves through NAT with **fixed documented IPs**, because the LMS and vendors
allowlist them. Redis TLS, Oracle TCPS, Kafka SASL_SSL — "it's on a private subnet" is
not encryption. Metrics on port 8081 bound to the host interface, authenticated even
there. India regions only.

---

## 16. Observability

### Pages

| Signal | Why |
|---|---|
| `MANUAL_REVIEW` > 0 | Money collected, no loan record |
| `UNRESOLVED` > 0 | Payment status never determined |
| `AUDIT WRITE FAILED` | A control failure, not an error |

### Warns

Oldest `PENDING` row > 30 min · `REJECTED` > 0 · stranded `IN_FLIGHT` reclaims · rate
permits near zero · LMS bulkhead rejections · webhook signature failures ·
`SESSION_CONFLICT` rate · unique-constraint violations

### Correlation

One id per request, in the MDC, on every audit row and LMS call row, and in the response
header. Accepted from the client only if it parses as a UUID.

In Loki, `correlationId` is a **field, not a label** — a per-request label is the standard
way to destroy a Loki install.

**Never logged:** OTP, payment token, full agreement number.

---

## 17. Failure modes

| Failure | Behaviour | Borrower impact |
|---|---|---|
| **Redis down** | 503 across request-serving services | **Total outage, by design** |
| Oracle down | 503; no challan can be issued | Total outage |
| LMS fetch down | Bulkhead + breaker, controlled error | Cannot start journeys |
| LMS posting down | Queue grows, backoff, alert on age | None — challan issued |
| SMS down | OTP dispatch fails, borrower told to retry | Cannot authenticate |
| Kafka down | Worker finds rows on its next poll | Posting later |
| One server dies | LB removes it; rows unlock; sweeper reclaims | None |
| `payment-service` down | Callbacks fail; gateway retries; reconciliation catches the rest | New payments blocked |
| Amounts do not reconcile | `REJECTED`, alert, no retry | None |
| Log write fails | Posting continues, alert raised | None |

**Runbook line that must exist:** *a Redis outage is a total outage by design; the correct
response is to restore Redis, not to degrade the application.*

---

## 18. Code state and conventions

### Modules

```
payment-platform/            Maven multi-module, parent = spring-boot-starter-parent 4.0.0
├── payment-shared/          BUILT — the contract
├── payment-auth/            BUILT through OTP verification
├── payment-fetch/           skeleton + README
├── payment-service/         skeleton + README
├── payment-posting/         skeleton + README
└── payment-worker/          skeleton + README
```

Build with `mvn clean install` — shared must install before the services resolve it.
`mvn -pl payment-auth test` runs with no infrastructure.

Six modules, **five runnable jars**: `payment-shared` is a plain library jar with no
`spring-boot-maven-plugin` and no main class, installed to the local repository so the
other five resolve it. The five service jars plus their `*.jar.original` artifacts make
eleven files in the `target/` directories and six real ones.

**Docker is the deployment unit.** One parameterised `docker/Dockerfile` builds all five
service images; `docker/docker-compose.yml` runs them with profiles (`redis`, `app`,
`worker`) across the six OCI servers; Redis runs in Docker in dev, UAT and production
from `docker/redis/redis.conf`. The full sequence — jars, images, scan, Redis host, one
server, then the rest — is in `DOCKER_DEPLOYMENT.md`.

Three Docker-specific traps, each of which fails quietly:

1. **Redis must not run on an application server.** An app deployment there logs out every
   borrower on all six servers, empties the upstream token cache, and resets the posting
   rate-limit bucket.
2. **`maxmemory-policy noeviction` is a security setting, not tuning.** Any eviction
   policy silently deletes anti-replay nonces and live session keys under memory
   pressure. `docker/redis/redis.conf` sets it; verify it with `CONFIG GET` after every
   Redis deployment.
3. **`management.server.address` must be `0.0.0.0` in a container.** The properties
   default to `127.0.0.1`, which is correct on bare metal and makes the metrics port
   unreachable by Prometheus inside a container. The container boundary plus a port
   published to the private IP is what keeps it private.
4. **`localhost` inside a container is that container's loopback.** Development uses the
   Redis already on the developer's machine at port 6363; from a container that is
   `host.docker.internal:6363`, and if the Redis container is attached to the compose
   network instead, it is the container name at **6379** — the published port and the
   container port are different numbers.

**Deployment granularity.** Each service has its own version variable in the env file, so
a change in one service recreates only that container; the other four keep serving.
`docker/plan-release.sh` derives the affected set from the git diff and enforces the one
exception: when `payment-shared` or the parent pom changes, all five move together,
because `payment-shared` owns the token format and the Redis key layout. A mixed set
presents as a token minted by one service being rejected by another, intermittently, which
reads as a load balancer fault.

### What `payment-shared` contains

`SessionKeys` (Redis layout), `PaymentTokenService` (issue + verify + partner variants),
`SessionKeyReader` (read-only key access + TTL touch), `LmsSnapshotStore`,
`EcdhKeyService`, `AesGcmCipherService`, the encryption advices, `AntiReplayFilter`,
`SessionContextFilter`, the upstream client layer (below), the entities and
repositories, `GlobalExceptionHandler`, `CorrelationIdFilter`, `ClientContext`, `Mask`,
`Hashing`.

### The upstream client layer

Every outbound HTTP call in the platform — LMS and SMS — goes through one transport.

```
UpstreamHttpClient      the only class that performs an HTTP call
  ├── UpstreamCall      one call, described: upstream, api name, url, headers,
  │                     body, audit context, classifier, redactor
  ├── UpstreamResponse  status + raw body + parsed JSON
  └── UpstreamOutcome   the audit verdict (SUCCESS / NOT_FOUND / FAILURE)

LmsClient               the contract: token, agreement fetch, OTS amount,
                        status check, post transaction
LmsApiClient            the single implementation of all of them
SmsApiClient            token + OTP dispatch, same pattern  (payment-auth)
UpstreamTokenCache      bearer tokens in Redis, cluster-wide, early expiry
InteractionRecorder     the one write to LMS_API_LOG
ApiName                 the API_NAME values
```

One class per LMS endpoint was the earlier shape and it does not scale: the connection,
the common headers, the timing, the audit write and the failure translation were copied
into each one. Now a new LMS endpoint is one method on `LmsApiClient` that builds an
`UpstreamCall`. Common headers (`tkn`, `fiid`) are assembled in one method per upstream
and a call site adds extras on top.

**Resilience is programmatic, not annotated, and this is not a style preference.**
`@Bulkhead` and `@CircuitBreaker` work through an AOP proxy, which is bypassed when one
method of a class calls another on `this`. In a consolidated client a business method
calls its own token method — that self-invocation would have run with no bulkhead and no
breaker, and the symptom would only appear the day the LMS token endpoint hung. The
transport is a separate bean and decorates the call explicitly, so it applies regardless
of who called whom. It also solves what an annotation cannot: an annotation's `name` is a
compile-time constant, so one shared transport could never be `"lms"` for one caller and
`"sms"` for another. The upstream name on the call resolves all three of the RestClient
bean (`<name>RestClient`), the bulkhead instance and the breaker instance.

Response classification runs **outside** the breaker. A "no such agreement" answer is a
business result, not an upstream fault, and must not push the breaker towards open.

A 401 or 403 from an upstream evicts the cached bearer and retries the call exactly
once — once, not in a loop, because a credential the upstream keeps refusing is a
configuration problem and hammering their token endpoint will not fix it.

### Conventions that must not drift

1. **Only `auth-service` writes `session:state`.** Enforce in code review.
2. **Token signs, never carries**, session id and agreement.
3. **Every state transition is conditional.** Zero rows is not an error.
4. **Errors are never encrypted.** `@SkipEncryption` on `GlobalExceptionHandler`.
5. **`FilterRegistrationConfig` in every service** that uses the shared filters, or they
   run twice and the nonce is consumed before the security chain sees it.
6. **`PathPatternRequestMatcher`** — Security 7 removed `AntPathRequestMatcher`.
7. **Jackson 3** — `tools.jackson.*`, java.time built in.
8. **No OTP, token or full agreement number in any log**, at any level.
9. **Audit writes are synchronous**; interaction and LMS-call logs are async.
10. **`app.security.trusted-proxy-count`** must match the real proxy chain or the audit
    trail records a fabricated address.

### Methods that still need the LMS contract

`SmsApiClient.token()` and `LmsApiClient.token()` — the request shapes for
`getSmsAccessToken()` and `getEXApiToken(fiid)` were never available. Both are marked
SEAM in the source; replace the request body and the response field name only, nothing
around them.

The three posting-phase LMS calls on `LmsApiClient` — `otsAmount()`, `statusCheck()` and
`postTransaction()` — are marked the same way. Their call sites and sequencing are settled
(see POSTING_ARCHITECTURE.md); only the request bodies and response field names are
pending from the LMS team.

### Frontend

Angular standalone, built through OTP verification. Crypto contract: P-256, raw
uncompressed public key, ECDH shared secret = X coordinate, HKDF-SHA256 with salt =
session id and info `payment-session-aes-256-gcm/v1`, AES-256-GCM with 12-byte IV and
16-byte tag sent separately, **AAD = session id**. The AAD is what people forget;
WebCrypto accepts its absence silently and the backend then rejects everything.

No `localStorage`, no `sessionStorage`. TLS required even locally — `__Host-` cookies are
discarded on plain HTTP.

---

## 19. Defects found in the legacy system

Context for why the new design looks the way it does.

| Defect | Consequence |
|---|---|
| `verifyGatewaySignature` returned `true`, result ignored | Anyone guessing a transaction id could forge payment success |
| OTP logged in plaintext at INFO | Every OTP ever issued went to the log pipeline |
| OTP stored plaintext in Redis and SQL | Docs claimed salted SHA-256 |
| Amount taken from the client, only `@DecimalMin("1.00")` | Amount tampering |
| Attempt counter read-modify-write on a session object | 3-attempt cap never bound under concurrency |
| `X-Accept-Plaintext: true` disabled response encryption | Advertised in the CORS allow-list |
| Anti-replay only ran if the client sent the headers | Replayers omitted them |
| AES key and ECDH private key persisted to SQL | One DB dump decrypts every captured session |
| Hardcoded fallback HMAC secret in `@Value` | Missing env var = forgeable tokens |
| IDOR: ownership check skipped when `boundTransactionId` was null | Any verified session could read any receipt |
| `memoryFallback` ConcurrentHashMap | Unbounded, node-local, divergent across servers |
| Idempotency get-then-set | Did not stop the double-click it existed for |
| H2 console enabled and `permitAll` | Unauthenticated SQL console if it reached an environment |
| No rate limiting anywhere | Agreement enumeration, SMS cost flooding |

---

## 20. Open questions

**Nine of twelve are LMS questions.** The largest project risk is a single external
dependency.

### LMS team

1. Is the post API **idempotent on a client reference**?
2. Does the **status-check API cover a whole transaction** or one call within it?
3. **Concurrent request ceiling** you will accept?
4. Committed **p95/p99 latency**, and month-end behaviour?
5. Ceiling in **transactions or API calls**?
6. Request shape for `getEXApiToken(fiid)`
7. Can `success[]` contain **different mobile numbers** for one customer?
8. What counts as an **amount mismatch** — exact, or a tolerance?
9. Do **FAILED callbacks** need posting, or stop at `NOT_APPLICABLE`?

### SMS provider

10. Is a **POST endpoint** available? Request shape for `getSmsAccessToken()`?

### Vendors and network

11. Callback **source IP ranges**; each vendor's **retry policy** and status API contract

### Internal

12. **Log retention** period and location; challan number format; **is the displayed
    payment allocation a promise to the borrower?** (§10 — a product decision)

---

## 21. Build order

1. `PAYMENT_POSTING_QUEUE` + `LMS_API_LOG`, challan sequence, unique constraints
2. Claim-and-transition primitives, **tested against real Oracle under concurrency**
3. `payment-worker` — posting executor, sweeper
4. `posting-service` — intake, challan, partner ingress
5. `payment-service` — lifecycle, vendor adapters, **callback signature verification**
6. `fetch-service` — reads the session snapshot
7. Reconciliation
8. Rate limiter
9. Load test: N workers on one queue, callback storms, LMS degradation, Redis failover

Steps 1 and 2 are the architecture. Everything after is plumbing around them.

Step 5's signature verification is the highest-value control in the platform.

---

## Companion documents

| Document | Detail beyond this one |
|---|---|
| `POSTING_ARCHITECTURE.md` | Full posting schema and sequence diagrams |
| `NETWORK_BUILD_SPEC.md` | CIDRs, security groups, WAF rules, NetworkPolicy YAML |
| `DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` | Image hardening, JVM flags, probes |
| `CAPACITY_AND_PERFORMANCE.md` | Sizing model, load test plan |
| `target-architecture-v2.svg/png` | Platform diagram |
| `posting-architecture.svg/png` | Posting flow diagram |

This document is the master. The others are detail for the people implementing each part.
