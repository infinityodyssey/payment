# Capacity and Performance Architecture — LMS Repayment Platform

> Companion to *Proposed Architecture*, *Network Architecture* and *Container and
> Deployment Architecture*. Target design only.

---

## 1. Read this first

**Every number in this document is a model, not a measurement.** Nothing here has been
load tested. The inputs marked `[INPUT]` are placeholders and must be replaced with your
real figures before any of it is quoted to anyone who will hold you to it.

What this document does give you is the shape of the problem: where the ceiling actually
is, which is not where most people assume.

---

## 2. The binding constraint

It is not CPU. It is not Redis. It is not Oracle. It is **threads blocked on the LMS**.

### The arithmetic

```
Tomcat max threads                200
LMS read timeout                   30s
Worst-case LMS-bound throughput   200 / 30  ≈  6.6 requests/second/instance
```

A Tomcat worker waiting on a 30-second LMS call is a worker doing nothing else. Virtual
threads would remove this limit; they are supported on JDK 25 and switched off by
decision, matching the existing production deployment model.

Under normal conditions the LMS answers far faster and the number is much higher:

| LMS p95 | Max LMS-bound rps per instance | Comment |
|---|---|---|
| 200 ms | 1,000 | thread pool is not the limit; CPU or Oracle is |
| 1 s | 200 | comfortable |
| 5 s | 40 | noticeable |
| 30 s (timeout) | **6.6** | collapse |

**This is the same arithmetic your current production application obeys.** If that app
runs a 200-thread pool and survives 50-second LMS stalls today, it is telling you that
peak concurrent LMS calls stay well under 200 — which is the most useful capacity
datapoint available, and it should set the numbers below rather than any estimate.

The problem is not the average. It is that **one degraded upstream takes down every
endpoint**, including `verify-otp`, which never calls the LMS at all. All 200 threads sit
in the LMS queue and nothing else gets a worker.

### The fix: bulkhead

Cap concurrent LMS calls below the thread count, using a semaphore bulkhead:

```
Tomcat threads                    200
LMS concurrent call limit         100     [INPUT — confirm against the current app]
Threads reserved for everything   100
```

Now LMS degradation costs at most 100 threads. `verify-otp`, status polling and webhook
handling continue on the remaining 100. Calls beyond the bulkhead fail fast with a
controlled error rather than queueing.

This is not redundant with the circuit breaker. The breaker reacts to *sustained* failure
once it has enough samples; the bulkhead bounds the first thirty seconds of a
degradation, which with a 30-second timeout is exactly when the damage happens.

The same applies to the SMS gateway at a smaller limit.

## 3. Cost per journey

One complete authentication journey, handshake through payment token:

| Resource | Count | Notes |
|---|---|---|
| HTTP requests | 3 | handshake, verify-agreement, verify-otp |
| ECDH keygen + derive | 1 | ~1–2 ms CPU, the only CPU-bound operation |
| AES-GCM operations | 4 | microseconds; negligible at these payload sizes |
| Redis round trips | ~15 | nonce, session CAS, OTP, counters, token cache |
| Oracle writes | 6 audit + 2 interaction | audit synchronous, interaction async |
| LMS calls | 1 | the expensive one |
| SMS calls | 1–2 | includes token fetch on cache miss |

### Where each tier saturates

| Tier | Capacity | Journeys/sec before it binds |
|---|---|---|
| Redis (single node) | ~100k ops/s | ~6,600 |
| Oracle (pool 20, 10 ms insert) | ~2,000 inserts/s/instance | ~250 |
| CPU (2 cores, 2 ms/handshake) | ~1,000 handshakes/s | ~1,000 |
| **LMS at p95 1s, bulkhead 100** | **100 concurrent** | **~100** |

**The LMS is the ceiling, by an order of magnitude.** Redis is nowhere near it. This is
the single most useful fact in this document, and it means capacity planning is mostly a
conversation with whoever owns the LMS, not with whoever owns the cluster.

---

## 4. Sizing model

Replace the inputs. The arithmetic then does itself.

```
[INPUT] Active borrowers                        A
[INPUT] Share repaying via the portal monthly   p
[INPUT] Share of those in the month-end window  q
[INPUT] Peak-window length in hours             h
[INPUT] Peak-hour concentration factor          k     (typ. 2.5–4)

Journeys in the window        J = A × p × q
Average rate                  R = J / (h × 3600)
Peak rate                     Rpeak = R × k
Required LMS concurrency      C = Rpeak × LMS_p95_seconds
Instances needed              N = C / bulkhead_limit   (rounded up, min 3)
```

### Worked example — **illustrative only, not your numbers**

```
A = 1,000,000          [INPUT — placeholder]
p = 0.25               [INPUT — placeholder]
q = 0.60               [INPUT — placeholder]
h = 12 (peak hours across the window)
k = 3

J     = 1,000,000 × 0.25 × 0.60   = 150,000 journeys
R     = 150,000 / (12 × 3600)     ≈ 3.5 journeys/sec
Rpeak = 3.5 × 3                   ≈ 10.4 journeys/sec
C     = 10.4 × 1s                 ≈ 11 concurrent LMS calls
N     = 11 / 100                  → 1, floored to 3 for availability
```

At these illustrative volumes the platform is availability-bound, not capacity-bound —
three instances for redundancy, not throughput. **That conclusion flips entirely if `A`
is ten times larger or the LMS p95 is five seconds instead of one.** Which is why the
inputs matter more than the design.

### What to ask the LMS team

1. What p95 and p99 latency do you commit to on the agreement-fetch endpoint?
2. What concurrent request ceiling will you accept from us?
3. What happens at month-end — do you degrade, and by how much?

Without answers, `maxReplicas` cannot be set responsibly, because scaling out into their
rate limit converts your capacity problem into their outage.

---

## 5. Month-end is the whole problem

EMI due dates cluster. This platform is not a steady-state system; it is a system with a
five-day spike every month, and the spike lands on the 1st to the 5th when the LMS is
also busiest.

| Consideration | Implication |
|---|---|
| Pre-scaling | scale up on a schedule before the window, not reactively — HPA reacts after users are already queuing |
| Upstream correlation | LMS and SMS gateway are slowest exactly when you need them most |
| SMS budget | per-agreement caps limit abuse, not legitimate month-end volume; confirm the gateway's throughput |
| Oracle growth | `LMS_INTERACTION_LOG` CLOBs grow fastest here; partition drop must keep up |
| Testing | load tests must model the spike shape, not an average |

---

## 6. Latency budget

Target p95 per endpoint, as something to measure against:

| Endpoint | Target p95 | Dominated by |
|---|---|---|
| `POST /auth/handshake` | 50 ms | ECDH keygen + 2 Redis ops |
| `POST /auth/verify-agreement` | LMS p95 + 300 ms | LMS, then SMS |
| `POST /auth/verify-otp` | 60 ms | Redis + 2 synchronous audit inserts |
| `GET /payment/status/{id}` | 40 ms | one Oracle read |
| `POST /payment/webhook/{vendor}` | 100 ms | signature verify + Oracle write |

Two things the borrower's browser needs to know about:

- `verify-agreement` inherits the LMS timeout. The Angular client's own timeout must
  exceed 30 seconds or it shows a failure while the backend is still working.
- The two synchronous audit writes in `verify-otp` are a deliberate trade: async writes
  get dropped on pod shutdown, and an audit row is the evidence an authentication
  happened. Losing an interaction row costs a debugging session; losing an audit row
  costs the evidence.

---

## 7. Load test plan

Nothing in this document is trustworthy until these run.

| # | Scenario | Passes if |
|---|---|---|
| 1 | Steady state at projected peak, 30 min | p95 within budget, no error rate above baseline |
| 2 | Month-end spike shape, 3× for 2 hours | autoscaling keeps p95 within budget |
| 3 | **LMS degraded to 20 s** | `verify-otp` and status polling stay healthy, and concurrent LMS calls never exceed the bulkhead limit — the most important test in the plan |
| 4 | LMS fully down | fast, controlled errors; circuit opens; no thread exhaustion |
| 5 | Redis failover | brief 503s, clean recovery, no state corruption |
| 6 | Oracle failover | pool recovers; audit failures alert loudly |
| 7 | Concurrent verify on one session | exactly one token issued, others get a conflict |
| 8 | Webhook storm with duplicate `tvstxnid` | idempotent, one terminal outcome |
| 9 | Rolling deploy under load | zero dropped requests, no lost interaction rows |
| 10 | Soak, 24 h | no heap growth, no connection leak, no Redis key growth |

Scenario 3 is the one that justifies the bulkhead. Scenario 7 is the one that justifies
the compare-and-swap. Both are currently untested claims.

---

## 8. Metrics that matter

Beyond the standard set:

| Metric | Why |
|---|---|
| `tomcat_threads_busy / max` | **the real saturation signal** — CPU stays low while threads exhaust |
| LMS bulkhead available permits | early warning before anything fails |
| Bulkhead rejection count | requests refused rather than queued; drives autoscaling |
| LMS / SMS call duration percentiles | upstream degradation before it becomes your outage |
| Circuit breaker state transitions | |
| `SESSION_CONFLICT` rate | non-zero means real concurrency, or a UI double-submit bug |
| `AUDIT WRITE FAILED` count | must alert — this is a control failure, not an error |
| Redis command latency and connection pool waits | |
| Oracle pool wait time | |
| OTP send budget rejections | abuse signal |
| Webhook signature failures | attack signal |

The first three are what an operator should watch during month-end. A CPU dashboard will
look calm while the platform is fully saturated.

---

## 9. Honest summary for the CTO conversation

- The platform's ceiling is set by the LMS, not by the application tier. Capacity
  planning is mostly an LMS conversation.
- A 30-second upstream timeout against a fixed thread pool is a denial-of-service risk
  against yourself. The bulkhead is the mitigation, it is built, and its limit should be
  set from the current production application's observed concurrency rather than an
  estimate.
- Redis availability equals service availability, by design. That is the correct trade
  for a payment flow, and whoever owns the SLA needs to have agreed to it.
- Java 25 LTS on platform threads is the configuration analysed here. Enabling virtual
  threads later removes the thread-pool ceiling entirely and is the single
  highest-leverage future change — but it also removes the implicit backpressure the
  pool provides, which makes the bulkhead load-bearing rather than protective.
- No numbers here are measured. The load test plan in section 7 is what converts this
  document from a model into a commitment.
