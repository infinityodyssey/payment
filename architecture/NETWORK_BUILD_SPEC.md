# Network Build Specification — LMS Repayment Platform

> Implementation-level companion to `NETWORK_ARCHITECTURE.md`. That document is the
> trust-boundary view for architecture review; this one is what an infra engineer builds
> from.

**Platform assumption: AWS.** Written against AWS naming. Azure and on-prem mappings are
in §12. Confirm the platform before this is implemented — the LB model and NAT design
change materially.

All addresses below are a **proposed** plan. Replace with ranges your network team has
allocated and confirmed do not overlap with existing VPCs, on-prem ranges, or any peered
network.

---

## 1. Address plan

### VPC allocation

| Environment | VPC CIDR | Usable | Region |
|---|---|---|---|
| prod | `10.40.0.0/16` | 65,536 | ap-south-1 (Mumbai) |
| uat | `10.41.0.0/16` | 65,536 | ap-south-1 |
| dev | `10.42.0.0/16` | 65,536 | ap-south-1 |

Non-overlapping by design so a future peering or transit-gateway attachment does not
require renumbering. `10.43.0.0/16` reserved for DR in ap-south-2 (Hyderabad).

### Prod subnets

Three AZs. Two would work; three means an AZ loss removes 33% of capacity rather than 50%.

| Subnet | CIDR | AZ | Type | Contains |
|---|---|---|---|---|
| `prod-public-1a` | `10.40.0.0/24` | 1a | public | ALB ENI, NAT GW |
| `prod-public-1b` | `10.40.1.0/24` | 1b | public | ALB ENI, NAT GW |
| `prod-public-1c` | `10.40.2.0/24` | 1c | public | ALB ENI, NAT GW |
| `prod-app-1a` | `10.40.16.0/20` | 1a | private | EKS nodes |
| `prod-app-1b` | `10.40.32.0/20` | 1b | private | EKS nodes |
| `prod-app-1c` | `10.40.48.0/20` | 1c | private | EKS nodes |
| `prod-data-1a` | `10.40.64.0/24` | 1a | isolated | RDS/Oracle, ElastiCache, MSK |
| `prod-data-1b` | `10.40.65.0/24` | 1b | isolated | replicas |
| `prod-data-1c` | `10.40.66.0/24` | 1c | isolated | replicas |
| `prod-mgmt-1a` | `10.40.80.0/24` | 1a | private | bastion, CI runners |

App subnets are `/20` (4,091 usable) because with the AWS VPC CNI **every pod consumes a
subnet IP**. At ~30 pods per node and 20 nodes that is 600 IPs per AZ; a `/24` would run
out during a surge and the failure presents as pods stuck in `ContainerCreating`, which
is not an obvious symptom of an addressing problem.

### Kubernetes ranges

| Range | CIDR | Notes |
|---|---|---|
| Pod CIDR | subnet IPs (VPC CNI) | pods are first-class VPC addresses |
| Service CIDR | `172.20.0.0/16` | cluster-internal, never routed |

### Route tables

| Table | Attached to | Routes |
|---|---|---|
| `rt-public` | public subnets | `0.0.0.0/0` → IGW; `10.40.0.0/16` → local |
| `rt-app-1a` | `prod-app-1a` | `0.0.0.0/0` → `nat-1a`; local |
| `rt-app-1b` | `prod-app-1b` | `0.0.0.0/0` → `nat-1b`; local |
| `rt-app-1c` | `prod-app-1c` | `0.0.0.0/0` → `nat-1c`; local |
| `rt-data` | data subnets | **local only — no default route** |

Per-AZ NAT, not one shared: a single NAT is an AZ-level single point of failure and adds
cross-AZ data transfer charges on every upstream call.

The data route table having no `0.0.0.0/0` entry is the control that makes exfiltration
from a compromised database host structurally impossible rather than policy-dependent.

---

## 2. Security groups

Referenced by security-group ID, never by CIDR, for anything inside the VPC. CIDR rules
drift as subnets change; group references do not.

### `sg-alb-public`

| Dir | Proto | Port | Source/Dest | Purpose |
|---|---|---|---|---|
| in | TCP | 443 | `0.0.0.0/0` | borrower HTTPS |
| in | TCP | 80 | `0.0.0.0/0` | redirect to 443 only |
| out | TCP | 8443 | `sg-app-node` | to pods |

### `sg-alb-webhook`

| Dir | Proto | Port | Source/Dest | Purpose |
|---|---|---|---|---|
| in | TCP | 443 | *vendor prefix list* | callbacks only |
| out | TCP | 8443 | `sg-app-node` | to pods |

Vendor source IPs go in a **managed prefix list** (`pl-payment-vendors`), not inline
rules. One object to update when a vendor changes ranges, and it is versioned and
auditable. No `0.0.0.0/0` on this listener under any circumstances.

### `sg-app-node`

| Dir | Proto | Port | Source/Dest | Purpose |
|---|---|---|---|---|
| in | TCP | 8443 | `sg-alb-public` | API |
| in | TCP | 8443 | `sg-alb-webhook` | callbacks |
| in | TCP | 8081 | `sg-app-node` | kubelet probes, Prometheus |
| in | TCP | 0-65535 | `sg-app-node` | pod-to-pod, refined by NetworkPolicy |
| out | TCP | 1521 | `sg-data-oracle` | Oracle |
| out | TCP | 6379 | `sg-data-redis` | Redis |
| out | TCP | 9093 | `sg-data-kafka` | Kafka |
| out | TCP | 443 | `0.0.0.0/0` | upstreams via NAT |
| out | UDP | 53 | VPC resolver | DNS |

Outbound 443 to `0.0.0.0/0` looks loose. Security groups cannot express "only these
hostnames", so the actual allowlist is enforced at the egress proxy in §5. If you skip
the proxy, this rule is the hole.

### `sg-data-oracle` / `sg-data-redis` / `sg-data-kafka`

| Dir | Proto | Port | Source | Purpose |
|---|---|---|---|---|
| in | TCP | *1521 / 6379 / 9093* | `sg-app-node` | application |
| in | TCP | *same* | `sg-mgmt-bastion` | break-glass |
| out | — | — | — | **none** |

No outbound rules at all. A database has no legitimate reason to originate a connection.

---

## 3. Load balancers

### Public ALB — `alb-payment-public`

```
Scheme          internet-facing
Subnets         prod-public-1a/1b/1c
Security group  sg-alb-public
Idle timeout    65s          ← must exceed the 30s LMS timeout plus overhead
Deletion prot   enabled
Access logs     s3://payment-alb-logs-prod/  (in-region)
HTTP/2          enabled
Drop invalid    enabled
```

| Listener | Action |
|---|---|
| :80 | 301 redirect to `https://#{host}#{path}?#{query}` |
| :443 | forward → `tg-payment-api` |

```
Listener :443
  Certificate     ACM, app.example.com
  Policy          ELBSecurityPolicy-TLS13-1-2-2021-06
  Target group    tg-payment-api
    Protocol      HTTPS :8443
    Health check  GET :8081 /actuator/health/readiness, 200, 10s, 2/3
    Deregistration delay  30s     ← must exceed the pod preStop sleep
    Stickiness    disabled
```

The 65-second idle timeout is not arbitrary. The LMS read timeout is 30 seconds; if the
ALB idles out first, the borrower gets a 504 while the backend is still working and the
resulting state is ambiguous.

Deregistration delay of 30s against a 5s `preStop` sleep and 25s graceful shutdown: the
ALB must stop sending traffic before the pod stops accepting it, or rolling deploys drop
requests.

### Webhook ALB — `alb-payment-webhook`

```
Scheme          internet-facing
Security group  sg-alb-webhook
Idle timeout    30s
Certificate     ACM, hooks.example.com    ← separate cert, separate FQDN
Policy          ELBSecurityPolicy-TLS13-1-2-2021-06
mTLS            verify mode, trust store s3://payment-vendor-ca/
Target group    tg-payment-webhook → :8443, path /api/v1/payment/webhook/*
```

Separate ALB rather than a host-based rule on the public one. Two reasons: a different
WAF policy attaches per-ALB, and mTLS is a listener-level setting that cannot be applied
to one host rule. A shared listener would force mTLS on borrowers or drop it for vendors.

ALB mTLS in `verify` mode passes the client certificate in
`X-Amzn-Mtls-Clientcert`. The application must still verify the HMAC signature in the
body — transport identity proves *which vendor connected*, not *that this message is
authentic*.

---

## 4. WAF

Two web ACLs, one per ALB. Sharing a policy is the mistake this design exists to prevent.

### `waf-payment-public`

| Pri | Rule | Action |
|---|---|---|
| 10 | `AWSManagedRulesCommonRuleSet` | block |
| 20 | `AWSManagedRulesKnownBadInputsRuleSet` | block |
| 30 | `AWSManagedRulesAmazonIpReputationList` | block |
| 40 | Geo: allow IN only | block others *(confirm — blocks NRI borrowers)* |
| 50 | Rate: 2000 req / 5 min per IP, all paths | block |
| 60 | Rate: 100 req / 5 min per IP, `/api/v1/auth/*` | block |
| 70 | Rate: 20 req / 5 min per IP, `/api/v1/auth/verify-otp` | block |
| 80 | Bot control, targeted | challenge |
| 90 | Body size > 32 KB on `/api/*` | block |

Rule 70 is the network-layer share of OTP protection. It is **not** a substitute for the
application's attempt cap and per-agreement send budget: those key on the agreement
number, which is inside an AES-GCM envelope the WAF cannot read. An attacker with a proxy
pool defeats rule 70 alone and is still stopped by the application controls. Anyone
proposing to consolidate all rate limiting at the edge has missed this.

Rule 40 needs a product decision. Geo-blocking is a large reduction in attack surface and
it blocks legitimate borrowers travelling or living abroad.

### `waf-payment-webhook`

| Pri | Rule | Action |
|---|---|---|
| 10 | Source not in `pl-payment-vendors` | block |
| 20 | Method not POST | block |
| 30 | Path not `/api/v1/payment/webhook/*` | block |
| 40 | Rate: 500 req / 5 min per IP | block |
| 50 | Body size > 64 KB | block |
| 60 | `AWSManagedRulesKnownBadInputsRuleSet` | block |

No common rule set here: vendor payloads legitimately contain patterns the generic SQLi
and XSS rules flag, and false positives on a payment callback mean a lost confirmation.
IP allowlisting plus mTLS plus signature verification is the stronger control for this
path.

---

## 5. Egress control

Security groups cannot allowlist hostnames. Two options:

| Option | Mechanism | Cost |
|---|---|---|
| A | AWS Network Firewall, TLS SNI rules | managed, ~$0.40/hr/endpoint + data |
| B | Squid forward proxy on the app subnets, `JAVA_TOOL_OPTIONS` proxy config | cheap, you run it |

Recommendation: **A**. It is enforced at the VPC route level, so a pod cannot bypass it by
ignoring proxy settings, and its logs are a clean exfiltration signal.

### Allowlist

| Destination | Port | Purpose |
|---|---|---|
| `lms-api.internal.example.com` | 443 | agreement lookup, token |
| `sms-gw.provider.example.com` | 443 | OTP dispatch |
| `api.paytm.com` | 443 | vendor |
| `api.atomtech.example` | 443 | vendor |
| `api.airtel.example` | 443 | vendor |
| `secretsmanager.ap-south-1.amazonaws.com` | 443 | secrets |
| `*.s3.ap-south-1.amazonaws.com` | 443 | logs, heap dumps |
| **everything else** | — | **deny + alert** |

No package registry at runtime. No general internet. A denied egress attempt should page
someone — it is either a misconfiguration or an intrusion, and both want a human.

### NAT

Elastic IPs must be **static and documented**, because your LMS and vendors will allowlist
them. A NAT replacement that changes the address breaks payments silently, at the vendor
end, where you will not see it first.

| NAT | EIP | AZ |
|---|---|---|
| `nat-prod-1a` | *allocate and record* | 1a |
| `nat-prod-1b` | *allocate and record* | 1b |
| `nat-prod-1c` | *allocate and record* | 1c |

Share all three with every upstream. A borrower request served from 1c leaves via
`nat-prod-1c`; allowlisting only one address produces intermittent failures that look
like random upstream errors.

---

## 6. Kubernetes NetworkPolicy

Security groups govern node-level traffic; NetworkPolicy governs pod-level. Both are
needed — an SG rule allowing `sg-app-node` to itself permits any pod to reach any pod.

```yaml
# Deny everything, then allow explicitly.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: payment-app
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: payment-backend-ingress
  namespace: payment-app
spec:
  podSelector:
    matchLabels: {app: payment-backend}
  policyTypes: [Ingress]
  ingress:
    # ALB target registration arrives from the node subnets.
    - from:
        - ipBlock: {cidr: 10.40.0.0/16}
      ports:
        - {protocol: TCP, port: 8443}
    # Metrics: monitoring namespace only. Basic auth applies on top of this.
    - from:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: monitoring}
      ports:
        - {protocol: TCP, port: 8081}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: payment-backend-egress
  namespace: payment-app
spec:
  podSelector:
    matchLabels: {app: payment-backend}
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: kube-system}
          podSelector:
            matchLabels: {k8s-app: kube-dns}
      ports:
        - {protocol: UDP, port: 53}
        - {protocol: TCP, port: 53}
    - to:
        - ipBlock: {cidr: 10.40.64.0/22}    # data subnets
      ports:
        - {protocol: TCP, port: 1521}
        - {protocol: TCP, port: 6379}
        - {protocol: TCP, port: 9093}
    # Upstreams. Narrowed to hostnames by Network Firewall, which NetworkPolicy
    # cannot express.
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16]
      ports:
        - {protocol: TCP, port: 443}
```

The `except` on RFC1918 ranges matters: without it, "allow outbound 443" lets a
compromised pod reach every internal service in every peered network on 443.

---

## 7. DNS

| Record | Type | Value | TTL |
|---|---|---|---|
| `app.example.com` | A alias | `alb-payment-public` | 60 |
| `hooks.example.com` | A alias | `alb-payment-webhook` | 60 |
| `cdn.example.com` | A alias | CloudFront | 300 |
| `lms-api.internal.example.com` | private zone | LMS endpoint | 300 |

60-second TTL on the public records so a DR cutover is minutes, not hours.

A **private hosted zone** for internal names: it resolves only inside the VPC, so the LMS
endpoint is not discoverable from public DNS enumeration.

Enable DNSSEC on the public zone if the registrar supports it. Enable Route 53 query
logging — a pod resolving a name that is not on the allowlist is one of the cheapest
compromise signals available.

---

## 8. TLS

| Where | Version | Notes |
|---|---|---|
| Browser → ALB | 1.3 preferred, 1.2 min | `ELBSecurityPolicy-TLS13-1-2-2021-06` |
| Vendor → webhook ALB | 1.2 min + mTLS | vendor CA in the trust store |
| ALB → pod | 1.2 | re-encrypted, not passthrough |
| Pod → Oracle | TCPS | `oracle.net.ssl_server_dn_match=true` |
| Pod → Redis | TLS | `spring.data.redis.ssl.enabled=true` |
| Pod → Kafka | SASL_SSL | `SCRAM-SHA-512` |
| Pod → LMS / SMS | 1.2 min | verify hostname; do not disable verification for a self-signed cert — import the CA |

HSTS is set by the application: `max-age=63072000; includeSubDomains; preload`. Submit to
the preload list only once you are certain every subdomain can serve HTTPS — it is slow
to reverse.

The `__Host-` session cookie requires `Secure`, so any environment without TLS end to end
silently breaks authentication.

---

## 9. Port reference

| Port | Component | Exposure |
|---|---|---|
| 443 | ALBs | internet |
| 80 | public ALB | internet, redirect only |
| 8443 | application API | ALB → pod |
| 8081 | actuator | node-local + monitoring ns |
| 1521 | Oracle TCPS | app + bastion |
| 6379 | Redis TLS | app + bastion |
| 9093 | Kafka SASL_SSL | app + bastion |
| 53 | DNS | pod → VPC resolver |
| 22 | bastion SSH | via SSM Session Manager, no open port |

Break-glass through SSM Session Manager rather than an SSH bastion with an open port:
no inbound rule, IAM-controlled, session-recorded.

---

## 10. Flow reference

The complete list. Anything not here is denied.

| # | Source | Destination | Port | Enforced by |
|---|---|---|---|---|
| 1 | Internet | CloudFront | 443 | — |
| 2 | Internet | `alb-payment-public` | 443 | `sg-alb-public` + `waf-payment-public` |
| 3 | Vendor IPs | `alb-payment-webhook` | 443 | `sg-alb-webhook` + prefix list + mTLS |
| 4 | `alb-payment-public` | pods | 8443 | `sg-app-node` + NetworkPolicy |
| 5 | `alb-payment-webhook` | pods | 8443 | `sg-app-node` + NetworkPolicy |
| 6 | kubelet | pods | 8081 | NetworkPolicy |
| 7 | Prometheus | pods | 8081 | NetworkPolicy + Basic auth |
| 8 | pods | Oracle | 1521 | `sg-data-oracle` + NetworkPolicy |
| 9 | pods | Redis | 6379 | `sg-data-redis` + NetworkPolicy |
| 10 | pods | Kafka | 9093 | `sg-data-kafka` + NetworkPolicy |
| 11 | pods | NAT → allowlist | 443 | Network Firewall |
| 12 | pods | kube-dns | 53 | NetworkPolicy |
| 13 | SSM | bastion | — | IAM |
| 14 | bastion | data tier | 1521/6379/9093 | SG + IAM + session recording |

---

## 11. Validation

Do not accept "it's configured." Test it.

| # | Test | Expected |
|---|---|---|
| 1 | `curl https://app.example.com/api/v1/payment/webhook/paytm` | blocked — wrong hostname |
| 2 | `curl https://hooks.example.com/api/v1/auth/handshake` | blocked — WAF path rule |
| 3 | Webhook from a non-allowlisted IP | blocked at WAF, before the app |
| 4 | Webhook without a client cert | TLS handshake fails |
| 5 | From a pod: `curl https://www.google.com` | blocked by Network Firewall, alert raised |
| 6 | From a pod: `nc -zv <oracle-ip> 22` | refused |
| 7 | From a pod in another namespace: `curl pod:8081` | blocked by NetworkPolicy |
| 8 | From the internet: `curl <oracle-endpoint>:1521` | unreachable, no route |
| 9 | `curl http://app.example.com` | 301 to HTTPS |
| 10 | TLS 1.0/1.1 handshake | rejected |
| 11 | Kill an AZ's NAT | traffic continues via other AZs |
| 12 | Rolling deploy under load | zero 5xx |
| 13 | `testssl.sh app.example.com` | A grade, no weak ciphers |

Tests 1 through 4 are the ones that prove the three-path separation actually exists
rather than being a diagram.

---

## 12. Platform mapping

| AWS | Azure | On-prem |
|---|---|---|
| VPC | VNet | VLAN / VRF |
| Security group | NSG | firewall policy |
| ALB | Application Gateway | F5 LTM / NGINX+ |
| WAF | Azure WAF on AppGW | F5 ASM / ModSecurity |
| NAT Gateway | NAT Gateway | firewall SNAT pool |
| Network Firewall | Azure Firewall | Palo Alto / FortiGate URL filtering |
| Managed prefix list | IP Group | address group |
| Secrets Manager | Key Vault | HashiCorp Vault |
| SSM Session Manager | Bastion | jump host |
| Route 53 private zone | Private DNS zone | internal DNS view |

The design holds on all three. Only the object names change.

---

## 13. Open items

1. **Platform confirmation** — this document assumes AWS.
2. Vendor callback source ranges, for `pl-payment-vendors`.
3. Vendor mTLS support, and their CA certificates.
4. Is the LMS reachable over Direct Connect / private link, or does it need public egress?
5. Geo-blocking: block non-India, or allow? Product decision.
6. CIDR ranges confirmed non-overlapping by the network team.
7. NAT Elastic IPs allocated, documented, and shared with LMS and all vendors.
