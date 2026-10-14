# Docker build and deployment

Source → jars → images → the six OCI servers.

Redis runs in Docker **for local development only**. UAT and production use the Redis the
infra team runs; nothing in this compose file starts one there. §6 is what to hand them.

---

## 1. How many jars

`mvn clean install` builds **six modules** and produces **five runnable jars**.

| Module | Runnable | Role |
|---|---|---|
| `payment-shared` | **No** | Library jar — no boot plugin, no main class. Installed so the other five resolve it. `java -jar` on it fails. |
| `payment-auth` | Yes | Handshake, ECDH session, OTP, payment and partner tokens. Port 8443. |
| `payment-fetch` | Yes | Customer and dues detail reads. Port 8444. |
| `payment-service` | Yes | Vendor eligibility, initiation, `tvstxnid`, status. Port 8445. |
| `payment-posting` | Yes | Vendor callback and partner posting ingress. Port 8446. |
| `payment-worker` | Yes | Queue drain to LMS, sweeper, reconciliation. No ingress. |

You will also see five `*.jar.original` — the pre-repackage artifacts, not deployable.

**Build from the root.** `payment-shared` must install before the five services resolve
it; `mvn package` inside a service directory fails on a missing dependency.

---

## 2. Running a jar directly, for validation

This is the path for handing someone a build to check against a real Oracle, with no
Docker involved.

Spring Boot automatically reads `./config/` next to the working directory — no flag,
nothing to remember:

```
validation/
  payment-auth-1.0.0-SNAPSHOT.jar
  config/
    application-local.properties     <- Oracle URL, user, password, the secrets
```

```bash
cd validation
java -jar payment-auth-*.jar --spring.profiles.active=dev,local
```

Start from `config/application-local.properties.example` in the repository. It is
git-ignored (`config/*.properties`), so only the `.example` is committed.

Why a file rather than `export ORACLE_PASSWORD=...`: an exported variable lands in shell
history and in `/proc` for anything on the box to read, and it is gone next time you open
a terminal — so people paste it into a script, and the script gets committed. A file
travels with the jar and `chmod 600` works on it.

**This is not a secrets manager.** It is right for a development or UAT credential a
human runs a jar with. Production credentials belong in OCI Vault or a root-owned env
file on that server, never in a file that travels alongside a jar. And if this file ever
lands in a commit, the password in it is burned — rotate it, do not just delete the file.

---

## 3. Local development

```bash
cd docker
cp env/dev.env.example env/dev.env          # fill in Oracle + the two secrets
docker compose --env-file env/dev.env --profile dev --profile app --profile worker up -d
```

`--profile dev` starts a Redis on this compose network, published to `127.0.0.1:6363`.
Already running your own on 6363? Leave that profile out and set `REDIS_HOST` /
`REDIS_PORT` per the comment in `dev.env.example`.

The port that confuses everyone:

| Connecting from | Host | Port |
|---|---|---|
| A container on the compose network | `redis` | **6379** |
| Your IDE, `mvn spring-boot:run`, `redis-cli` | `localhost` | **6363** |
| A container → your own host Redis | `host.docker.internal` | your published port |

6379 is the container port, 6363 is the host port — `-p 6363:6379`. Which one is right
depends on which side you are calling from. And `localhost` inside a container is *that
container's* loopback: it reaches nothing, whatever port you give it.

`application-dev.properties` in all five services defaults to `localhost:6363`, so running
a service from the IDE needs no environment variables for Redis at all.

---

## 4. Build

```bash
mvn clean install                                    # 5 runnable jars
./docker/build-images.sh 1.0.0                       # 5 images
./docker/build-images.sh 1.0.1 registry.internal/payment --only auth --push
```

Scan before shipping — a VA every six months is the requirement, a scan per build is
cheaper than finding it six months in:

```bash
docker scout cves registry.internal/payment/payment-auth:1.0.0   # or trivy image ...
```

**No `latest` tag.** A floating tag means a restart can silently change the running code,
and the image that passed the scan is not necessarily the one serving borrowers.

What the image does, and why:

| | |
|---|---|
| Non-root uid 10001, read-only root fs, `/tmp` as `noexec` tmpfs, all caps dropped | The app writes nothing to disk; everything is in Oracle or Redis. |
| `MaxRAMPercentage=70` | Percentage of the *container* limit. 70 leaves room for metaspace, thread stacks and Netty direct buffers — the off-heap memory that gets a container OOM-killed. |
| `+ExitOnOutOfMemoryError` | Die, let Docker restart. A JVM that survives an OOM limps: requests fail at random, the breakers conclude the LMS is fine, and nothing reports a problem. |
| **No** `HeapDumpOnOutOfMemoryError` | A dump here holds live AES session keys, OTPs in flight and the token HMAC secret. Enable per investigation, not by default. |
| `exec java …` | The JVM becomes PID 1 and gets SIGTERM, so graceful shutdown runs. Without `exec` it is killed at the stop timeout, cutting an in-flight LMS posting. |
| `stop_grace_period: 45s` | Longer than the 30s LMS read timeout, so a container stopped mid-posting sees the call finish. |

---

## 5. Deploy

All five services run on every server, one instance each — four browser-facing plus two
vendor-facing, per the agreed shape.

```bash
cd docker
cp env/prod.env.example env/prod.env        # PRIVATE_IP is THIS server's address
chown root:docker env/prod.env && chmod 0640 env/prod.env

docker compose --env-file env/prod.env --profile app --profile worker config --quiet
docker compose --env-file env/prod.env --profile app --profile worker pull
docker compose --env-file env/prod.env --profile app --profile worker up -d
```

**One server at a time, out of the load balancer pool.** The first server is what finds
the configuration mistake.

Verify before returning it to the pool:

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'      # all healthy (start-period 90s)

for p in 8081 8082 8083 8084 8085; do
  echo -n "$p: "; curl -s "http://$PRIVATE_IP:$p/actuator/health/readiness"; echo
done

# which build is actually on this server
docker inspect payment-auth --format '{{index .Config.Labels "org.opencontainers.image.version"}} {{index .Config.Labels "org.opencontainers.image.revision"}}'

# the check that saves the most time later
docker compose --env-file env/prod.env exec auth printenv PAYMENT_TOKEN_HMAC_SECRET | sha256sum
```

That last one matters: a server whose token secret differs rejects every token issued
elsewhere, and because the load balancer spreads requests it shows up as intermittent auth
failures on ~1 request in 6 — which reads as a load balancer fault. The hash must be
identical on all six.

Then run one real journey through the server (handshake → verify-agreement → OTP →
verify-otp) and confirm the `LMS_API_LOG` rows carry the correlation id.

### Three settings that are decisions, not defaults

- **`LMS_MAX_CONCURRENT` is per service instance.** 6 servers × 4 LMS-calling services ×
  100 = 2,400 concurrent calls, almost certainly more than the LMS agreed to. Divide the
  agreed figure. Getting it wrong fails as a throttle on their side at month-end, not as
  an error here.
- **Worker count.** Six workers claiming from the queue is safe for duplicates
  (`SKIP LOCKED` plus the shared `group.id`) but not for rate: the agreed posting ceiling
  (300/min, later 600) is a platform total. Either divide it or share the Redis token
  bucket at `rl:lms-posting`. To run fewer, omit `--profile worker` on those servers.
- **`TRUSTED_PROXY_COUNT`.** The real number of proxies that append to `X-Forwarded-For`
  — gateway plus LB is 2. Too high and the audit row records an address the caller chose,
  which is worse than recording none.

### Redeploying one service

Each service has its own version variable, so a fix in one does not restart the other
four:

```ini
APP_VERSION=1.0.0      # what the platform is on
AUTH_VERSION=1.0.1     # auth alone moves
```

```bash
docker compose --env-file env/prod.env --profile app --profile worker up -d auth
```

**The exception is `payment-shared`.** It owns the payment token format, the Redis key
layout and the session contract. When it changes, all five move together — clear the
`*_VERSION` overrides and bump `APP_VERSION`. A mixed set is a protocol mismatch, and it
surfaces as a token minted by one service being rejected by another on whichever requests
cross the version boundary.

### Rollback

The previous version number — the reason not to use `latest`:

```bash
sed -i 's/^APP_VERSION=.*/APP_VERSION=1.0.0/' env/prod.env
docker compose --env-file env/prod.env --profile app --profile worker up -d
```

**Rolling back the application does not roll back Oracle.** The DDL under
`payment-auth/src/main/resources/db` is forward-only. A release that adds a column is safe
to roll back; one that changes a constraint is not.

---

## 6. What the infra team needs to know about the real Redis

One instance serves all six servers — that is what makes a session survive the load
balancer sending the next request elsewhere. The header of `docker/redis-dev.conf` is
written to be handed over; the substance:

**It is a datastore, not a cache.** It holds the only copy of live sessions, AES session
keys, anti-replay nonces, cached upstream bearer tokens and the posting rate-limit bucket.
Nothing re-creates them.

| Setting | Why |
|---|---|
| `maxmemory-policy noeviction` | Stock Redis already defaults to this. The risk is someone tuning it like a cache and choosing an LRU policy — which several managed Redis services default to. Under LRU, Redis silently deletes keys: an evicted nonce re-opens the replay window, an evicted session key kills a payment mid-journey with nothing in any log. |
| `maxmemory` set below host RAM | Stock Redis defaults to 0 — unlimited — so it grows until the host OOM-killer picks a victim. With a limit *and* `noeviction` it refuses writes instead: callers get a controlled error and the alert fires. |
| `appendonly yes` | A restart otherwise ends every active session and empties the token cache for all six servers at once. |
| `requirepass` and TLS | What crosses the wire is AES session keys and OTP hashes. On a flat subnet, anything that reaches the port can decrypt a borrower's traffic. |

Also: not on an application server. A deployment there would take Redis down with it.

---

## 7. Day-2

**Prometheus** scrapes the published management ports on each server's private IP with the
actuator credential:

```yaml
scrape_configs:
  - job_name: payment-platform
    basic_auth: { username: prometheus, password: <ACTUATOR_PASSWORD> }
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['10.0.1.11:8081','10.0.1.11:8082','10.0.1.11:8083','10.0.1.11:8084','10.0.1.11:8085']
        labels: { server: app-01 }
```

Alert on these four first:

| Metric | Why |
|---|---|
| `resilience4j_bulkhead_available_concurrent_calls{name="lms"}` near zero | The LMS is degrading. Moves before anything customer-visible does. |
| `resilience4j_circuitbreaker_state{name="lms"}` open | Journeys cannot start. |
| Redis `used_memory` against `maxmemory` | With `noeviction`, hitting it stops writes and stops payments. |
| Posting queue depth / oldest `PENDING` row age | Drain rate below arrival rate, or the worker is dead. |

**Logs.** `logback-spring.xml` writes JSON to stdout; Promtail or Alloy ships it to Loki.
The rotation in the compose file (100m × 5) is a disk-full guard, **not** retention — the
180-day requirement is a Loki setting, in India. Keep Loki labels low-cardinality
(`app`, `env`, `level`, `server`); `correlationId` is a field, not a label.

**Host NTP.** A container reads the host clock. An unsynced host breaks the 60-second
anti-replay window — valid requests rejected as stale, intermittently, on one server.
Sync to NIC or NPL.

---

## 8. Failures you will actually hit

| Symptom | Cause | Fix |
|---|---|---|
| Container unhealthy, readiness DOWN | Oracle or Redis unreachable from the container | Check the security list, not the app |
| Connection refused to Redis from a container | `REDIS_HOST=localhost` — the container's own loopback | `redis:6379` with `--profile dev`, or `host.docker.internal` + your published port |
| Prometheus gets connection refused on 8081 | `MANAGEMENT_BIND_ADDRESS` left at `127.0.0.1` | Set `0.0.0.0`; the port binding to `PRIVATE_IP` is the control |
| Oracle auth fails with a correct password | `$` in the password eaten by Compose | Double it: `$$` |
| Auth fails on ~1 request in 6 | `PAYMENT_TOKEN_HMAC_SECRET` differs on one server | Compare the sha256 on all six |
| A one-line fix restarted all five | `APP_VERSION` bumped instead of the one `*_VERSION` | Set only that service's version line |
| All sessions drop at once, no deployment | Redis restarted, or keys evicted | Check `maxmemory-policy` and `appendonly` on the real Redis (§6) |
| `PRIVATE_IP must be set` | `--env-file` not passed | Pass it on every compose command |
| Jar not found during image build | `mvn clean install` not run, or run inside a module | Build from the repository root |
| Anti-replay rejects valid requests intermittently | Host clock drift | NTP on every host |

---

## 9. Files

```
payment-platform/
├── config/application-local.properties.example   credentials for running a jar directly
├── .dockerignore
└── docker/
    ├── Dockerfile              one definition, all five services (ARG SERVICE)
    ├── docker-compose.yml      profiles: dev | app | worker
    ├── build-images.sh         five images, or --only a subset
    ├── redis-dev.conf          local Redis; its header is the brief for the real one
    └── env/{dev,uat,prod}.env.example
```
