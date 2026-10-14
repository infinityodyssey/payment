# Architecture document set — LMS Repayment Platform

Companions to *Proposed Architecture — LMS Repayment Platform*. All target design.
None of it describes a current deployment, and none of the performance figures are
measured.

| Document | Answers | Audience |
|---|---|---|
| **`HANDOFF_MASTER.md`** | **everything + the decision log and rejected alternatives — START HERE** | **anyone new, or a new assistant** |
| `MASTER_DESIGN.md` | consolidated design, no decision log | design review |
| `NETWORK_ARCHITECTURE.md` | what can reach what, and why — trust boundaries | CTO, security review |
| `NETWORK_BUILD_SPEC.md` | CIDRs, security group rules, WAF rules, NetworkPolicy YAML | infra engineer implementing it |
| `DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` | how it is built, shipped, scaled and recovered | platform, SRE |
| **`DOCKER_DEPLOYMENT.md`** | **the actual build and deploy runbook: jars, images, the six servers, Redis** | **whoever deploys it** |
| `CAPACITY_AND_PERFORMANCE.md` | where the ceiling is and how much it handles | CTO, capacity planning |

> **Which deployment document to follow.** `DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` is
> the earlier design study and still refers to an orchestrated, auto-scaled shape
> (`maxReplicas`, NetworkPolicy, three ingress paths). The agreed deployment is six OCI
> servers running Docker Compose, one instance of each service per server, with two
> ingress paths. `DOCKER_DEPLOYMENT.md` is what matches the code and the decision, and it
> is the one to execute. Read the older document for the reasoning, not the commands.

## The three things a reviewer should take away

**1. The ceiling is the LMS, not the application.** A 30-second upstream timeout against
a 200-thread pool is an arithmetic ceiling of ~6.6 requests/second/instance in the worst
case, and one slow upstream starves every endpoint including those that never call it.
A concurrency bulkhead (100 LMS, 30 SMS) reserves workers for the endpoints that do not
touch the LMS. `maxReplicas × bulkhead_limit` must stay inside whatever concurrency the
LMS accepts. See `CAPACITY_AND_PERFORMANCE.md` §2.

**2. Three inbound paths, not one.** Borrower traffic, vendor callbacks and the
monitoring plane are three trust populations with three authentication mechanisms.
Vendor callbacks get their own hostname, their own certificate, their own WAF policy and
an IP allowlist — never the borrower listener. See `NETWORK_ARCHITECTURE.md` §4.

**3. Redis availability equals service availability, deliberately.** Session state,
nonces, OTP hashes and concurrency versions live only in Redis with no local fallback,
because degrading to per-node state silently disables replay protection and attempt
counting across a cluster. That is the correct trade for a payment flow, and whoever owns
the SLA needs to have agreed to it in advance rather than discovered it during an
incident.

## Contradiction in the parent document that needs resolving

"Scale stateless app instances horizontally" and the WebSocket push channel cannot both
be true without a broker relay or Redis pub/sub fan-out. A WebSocket connection is pinned
to one pod; the Kafka consumer that receives the status event will usually be on a
different one. Options and a recommendation are in
`DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` §6. The recommendation is to ship polling
first.

## Rendering

Mermaid diagrams do not render in VS Code's built-in Markdown preview. Install the
`bierner.markdown-mermaid` extension. GitHub, GitLab and Confluence render them natively.

## Blocking questions

Carried from the individual documents, consolidated:

1. **Cloud platform.** `NETWORK_BUILD_SPEC.md` assumes AWS; Azure and on-prem mappings
   are in its §12, but the LB and NAT designs differ materially.
2. Real borrower volumes and month-end spike shape — without these, every capacity figure
   is a placeholder.
3. LMS committed p95/p99 and the concurrent-request ceiling they will accept — this sets
   the bulkhead limit and `maxReplicas` together, and neither can be chosen without it.
4. Vendor mTLS support and callback source IP ranges.
5. SMS provider: is a POST endpoint available? The current GET puts the OTP in a query
   string, which lands in access logs on hops outside your control.
6. WebSocket — required for launch, or deferred?
7. ~~Distroless or UBI9 base image.~~ **Resolved:** `eclipse-temurin:25-jre-noble`,
   non-root, read-only root filesystem, no HTTP client in the image. Pin by digest
   before production. See `DOCKER_DEPLOYMENT.md` §5.
8. Secrets manager, and whether workload identity is available.
9. Log retention period and storage location. **Partly resolved:** stdout JSON ->
   json-file -> Promtail -> Loki, and the 180-day retention must be configured in
   Loki, in India. The Docker log rotation is a disk-full guard, not retention.
   Still open: who owns the Loki retention setting and confirms it in writing.
