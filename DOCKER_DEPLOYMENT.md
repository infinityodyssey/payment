# Docker build and deployment — EMI repayment platform

Covers the whole sequence: source → jars → images → running containers on the six OCI
servers, with Redis in Docker for development, UAT and production.

Read §1 before you plan the Redis host. Four of the five decisions in it are cheap now
and expensive after go-live.

---

## 1. Five things Docker changes, that will bite otherwise

### 1.1 Redis must not run on an application server

Redis is the whole platform's shared state: live sessions, the AES session keys, the
anti-replay nonces, the cached LMS and SMS bearer tokens, and the posting rate-limit
bucket. One instance serves all six servers — that is what makes a session survive the
load balancer sending the next request elsewhere.

Put that container on one of the six application servers and an ordinary app deployment
on that host becomes a platform-wide outage: `docker compose down` logs out every
borrower on all six servers, drops the cached upstream tokens so all six servers hit the
LMS token endpoint at once, and resets the posting rate-limit bucket so the worker can
briefly burst past the ceiling the LMS team agreed to.

**Put Redis on its own host, or on the database host.** Nowhere that gets an application
deployment. The accepted single-point-of-failure risk in HANDOFF_MASTER.md was "Redis
goes down" — not "every app deployment takes Redis with it."

### 1.2 The Redis eviction policy is a security setting

Docker's `redis` image sets no `maxmemory` at all, so Redis grows until the host runs out
and the kernel OOM-killer picks something — often an application container rather than
Redis. And if you set `maxmemory` without also setting the policy, the default evicts
keys silently to make room. In this keyspace that means:

| Key evicted | What actually happens |
|---|---|
| `nonce:*` | The replay window `AntiReplayFilter` closes re-opens. Security control gone, silently. |
| `session:key:*` | A live session dies mid-payment with no explanation anywhere. |
| `session:state:*` | An OTP-verified borrower is sent back to the start. |
| `rl:lms-posting` | The worker bursts past the agreed LMS posting rate. |

`docker/redis/redis.conf` sets `maxmemory-policy noeviction`, so a full Redis refuses
writes instead. Callers get `StateStoreUnavailableException`, borrowers see a controlled
error, and the alert fires. That is a bad afternoon — silent eviction is an incident you
hear about from the LMS team or an auditor.

The same file turns on AOF persistence, for the same reason: without it, replacing the
Redis image mid-day ends every active payment journey.

### 1.3 `management.server.address=127.0.0.1` is correct on metal and wrong in a container

The properties bind the actuator to loopback, which on bare metal is what keeps metrics
off the network. A container has its own network namespace, so inside one, loopback is
private to that container and Prometheus — running anywhere else — cannot reach it at
all. The container boundary already provides what the bare-metal setting was buying.

The compose file therefore sets `MANAGEMENT_BIND_ADDRESS=0.0.0.0` in the container, and
what keeps the port off the internet is the published binding: `${PRIVATE_IP}:8081:8081`,
never `0.0.0.0:8081`. **This matters more than it looks.** On most Docker installs a port
published to `0.0.0.0` bypasses host firewall rules, because the iptables rules Docker
inserts are evaluated before the ones a hardening script adds. "The security list blocks
it" is not reliably true for a published port.

### 1.4 Secrets in env files, and the `$` trap

Compose expands `$` inside env files. A password or bcrypt hash containing `$` — and
every bcrypt hash starts `$2a$12$` — gets silently mangled unless you double it to `$$`.
The failure presents as an authentication error against Oracle or a partner, pointing at
the wrong cause entirely.

And a 0600 env file is the baseline, not the goal: anything that can run `docker inspect`
can read every value in it, as can anything that reads `/proc/<pid>/environ`. If OCI
Vault is available, pull `PAYMENT_TOKEN_HMAC_SECRET`, `PAYMENT_OTP_PEPPER`,
`ORACLE_PASSWORD` and `REDIS_PASSWORD` at container start instead.

### 1.5 Development Redis: `localhost` means two different things

You already have Redis running locally on port **6363**, so the platform does not start
one in development — `--profile redis` is for UAT and production, where the platform owns
that instance.

Where it gets confusing is that the right host name depends on where the services run:

| Running the services | `REDIS_HOST` | Port | Why |
|---|---|---|---|
| IDE / `mvn spring-boot:run` | `localhost` | `6363` | Same machine. This is the built-in default in `application-dev.properties`, so no environment variables are needed at all. |
| In containers (compose) | `host.docker.internal` | `6363` | `localhost` inside a container is **that container's** loopback. There is no Redis in there, so `localhost:6363` can never reach yours — the port number is irrelevant. |
| Your Redis joined to the compose network | its container name | **`6379`** | On the Docker network, Redis still listens on 6379. `6363` is only the host-side half of `-p 6363:6379`. |

The compose file maps `host.docker.internal` to the host gateway explicitly, so the same
env file works on a Linux host as well as Docker Desktop.

If you would rather use the container name, attach your Redis to the project network and
switch the port to 6379:

```bash
docker network connect payment-platform_payment my-redis
```

**Know what your local Redis is configured as**, because it is almost certainly Docker's
defaults — no `maxmemory`, no AOF, and an eviction policy that will silently drop nonces
and session keys:

```bash
redis-cli -p 6363 CONFIG GET maxmemory-policy appendonly maxmemory
```

That is survivable locally, and it is also why a bug that only appears under memory
pressure will not appear in development. UAT must run `docker/redis/redis.conf` — the
settings in it, `noeviction` above all, are precisely the ones most likely to surprise
you.

---

## 2. How many jars

`mvn clean install` at the root builds **six modules** and produces **five runnable
jars**.

| Module | Artifact | Runnable | Role |
|---|---|---|---|
| `payment-shared` | `payment-shared-1.0.0-SNAPSHOT.jar` | **No** | Plain library jar. No `spring-boot-maven-plugin`, no main class. Installed to the local Maven repository so the other five resolve it. `java -jar` on it fails. |
| `payment-auth` | `payment-auth-1.0.0-SNAPSHOT.jar` | Yes | Handshake, ECDH session, OTP, payment token, partner token. Port 8443. |
| `payment-fetch` | `payment-fetch-1.0.0-SNAPSHOT.jar` | Yes | Customer and dues detail reads. Port 8444. |
| `payment-service` | `payment-service-1.0.0-SNAPSHOT.jar` | Yes | Vendor eligibility, initiation, `tvstxnid`, status. Port 8445. |
| `payment-posting` | `payment-posting-1.0.0-SNAPSHOT.jar` | Yes | Vendor callback and partner posting ingress. Port 8446. |
| `payment-worker` | `payment-worker-1.0.0-SNAPSHOT.jar` | Yes | Queue drain to LMS, sweeper, reconciliation. No ingress. |

You will also see five `*.jar.original` files. Those are the pre-repackage artifacts
`spring-boot-maven-plugin` leaves behind — not deployable, excluded in `.dockerignore`.
So eleven files in the `target/` directories, six real artifacts, five things you can run.

**Build order is not optional.** `payment-shared` must install before the five services
resolve it. Running `mvn package` inside a service directory without having installed
shared first fails on a missing dependency. Always build from the root.

---

## 3. Prerequisites on each host

```bash
docker --version          # 24.0 or newer; Compose V2 is required for `profiles`
docker compose version    # v2.20+ for the compose syntax used here
```

Four host-level items that are not Docker's job:

1. **NTP.** A container reads the host clock. An unsynced host breaks the 60-second
   anti-replay window — valid requests get rejected as stale, intermittently, on one
   server out of six. For an Indian regulated entity, sync to NIC or NPL servers.
2. **Disk.** Five containers plus images plus the local log buffer. Budget 40GB for
   `/var/lib/docker` and watch it; a full Docker filesystem stops containers starting with
   an error that does not mention disk.
3. **Log shipping.** `logback-spring.xml` writes JSON to stdout, which the json-file
   driver captures. Promtail or Grafana Alloy ships those to Loki. The rotation in the
   compose file (`100m` × 5) is a local buffer so a log flood cannot fill the disk — it is
   **not** retention. If CERT-In's 180-day requirement is to be met, that retention is set
   in Loki, in India, and the compose rotation is irrelevant to it.
4. **The private IP.** `PRIVATE_IP` in the env file is *that server's* VCN address and is
   different on each of the six.

After unzipping, restore the executable bit — zip archives do not always carry it:

```bash
chmod +x docker/build-images.sh docker/healthcheck.sh
```

`healthcheck.sh` is also `chmod 0555` inside the Dockerfile, so the image is correct
either way; `build-images.sh` can be run as `bash docker/build-images.sh ...` if you would
rather not change the mode.

---

## 4. Build the jars

### Option A — Maven on the build host (needs JDK 25 and Maven 3.9+)

```bash
cd payment-platform
mvn clean install
```

Verify:

```bash
ls -la */target/*.jar | grep -v original
# expect 6 lines: payment-shared plus the five services
```

### Option B — build in a container (build host needs only Docker)

Use this when the OCI build server has no JDK 25, or when you want the same compiler on
every build:

```bash
docker build -f docker/Dockerfile.builder --target jars --output type=local,dest=. .
```

The five service jars land in each module's `target/` exactly as Maven would leave them.
The Maven repository is a BuildKit cache mount, so it is not baked into any layer — no
dependency jar and no `settings.xml` credential ends up in a pushed image.

**Do not skip tests to get a release out.** The token and crypto suites in
`payment-auth/src/test` are the only automated check that a token issued for one session
cannot be replayed into another, or that a tampered AES envelope is rejected.

```bash
mvn -pl payment-auth test     # needs no Oracle, no Redis, no network
```

---

## 5. Build the images

```bash
./docker/build-images.sh 1.0.0
```

Five images, one version tag, from the already-built jars. With a registry and a push:

```bash
./docker/build-images.sh 1.0.0 registry.internal.tvscredit.co.in/payment --push
```

Or all of it in one step: `./docker/build-images.sh --with-maven 1.0.0`.

Only what changed, when that is all that changed:

```bash
./docker/build-images.sh 1.0.1 registry.internal.tvscredit.co.in/payment --only auth
```

**Build all five, deploy what changed.** The jars come out of one Maven reactor at one
version because they share `payment-shared`; the images and the restarts do not have to
follow in lockstep. §7 has the rule and the tooling, including the one case —
`payment-shared` itself changing — where all five must move together.

**No `latest`, ever.** A floating tag means a restart can silently change the running
code, and the image that passed your vulnerability assessment is not necessarily the one
serving borrowers.

### Scan before shipping

```bash
docker scout cves registry.internal.tvscredit.co.in/payment/payment-auth:1.0.0
# or: trivy image --severity HIGH,CRITICAL <image>
```

A vulnerability assessment every six months is the requirement; a scan on every build is
cheaper than finding it six months in. This is also why the runtime image has no curl and
no wget — every package is a line item in that assessment, and an HTTP client in the
image is also a tool for reaching the LMS or exfiltrating. The health probe uses bash's
`/dev/tcp` instead.

### What the image does and does not do

| Setting | Why |
|---|---|
| Non-root uid 10001 | A compromised process cannot write to the image or install anything. |
| `read_only: true` + tmpfs `/tmp` | The app writes nothing to disk. Tomcat's temp files live in RAM, `noexec` so nothing dropped there can run. |
| `cap_drop: ALL`, `no-new-privileges` | Nothing here needs a capability. |
| `MaxRAMPercentage=70` | Percentage of the *container* limit. 70 leaves room for metaspace, thread stacks and Netty direct buffers — the off-heap memory that actually gets a container OOM-killed. |
| `+ExitOnOutOfMemoryError` | Die and let Docker restart. A JVM surviving an OOM limps: requests fail at random, the circuit breakers conclude the LMS is fine, and nothing reports a problem. |
| **No** `HeapDumpOnOutOfMemoryError` | A heap dump here contains live AES session keys, OTPs in flight, borrower mobile numbers and the token HMAC secret. Writing that to a volume is a payment-credential disclosure path no log filter covers. Enable per-investigation, on an encrypted volume, with agreed retention. |
| `exec java …` in the entrypoint | The JVM becomes PID 1 and gets SIGTERM, so graceful shutdown runs. Without `exec`, the JVM is SIGKILLed at the stop timeout and an in-flight LMS posting is cut mid-call — the exact state the status check then has to resolve. |
| `stop_grace_period: 45s` | Longer than the 30s LMS read timeout, so a container stopped during a posting sees that call finish and records its outcome. Docker's default 10s manufactures unknown-state postings. |

---

## 6. Deploy

### 6.1 The six servers

Per the earlier decision, the whole application runs on every server, one instance each.

| Role | Count | Ingress | Services |
|---|---|---|---|
| Browser-facing | 2 | Path 1: API gateway → LB | all five |
| Vendor-facing | 4 | Path 2: allowlisted webhook | all five |
| Redis | 1 (separate) | none | `redis` only |

One compose file, selected by profile:

```bash
--profile redis     # Redis only. The Redis host.
--profile app       # auth, fetch, service, posting.
--profile worker    # payment-worker.
```

### 6.2 Prepare the env file (on each server, not in git)

```bash
cd payment-platform/docker
cp env/prod.env.example env/prod.env
vi env/prod.env                      # PRIVATE_IP is this server's address
chown root:docker env/prod.env && chmod 0640 env/prod.env
```

`--env-file` does two jobs: it supplies values for substitution in the compose file, and
the `ENV_FILE=` line inside it is what hands the same file to the containers. Both are
needed, which is why `ENV_FILE` is set in the file rather than passed separately.

Generate the secrets once, then copy the same values to all six servers:

```bash
openssl rand -base64 48    # PAYMENT_TOKEN_HMAC_SECRET
openssl rand -base64 48    # PAYMENT_OTP_PEPPER
openssl rand -base64 32    # REDIS_PASSWORD
```

**`PAYMENT_TOKEN_HMAC_SECRET` must be byte-identical on all six servers.** A token is
signed by auth and verified by the others; a server with a different secret rejects every
token issued anywhere else. Because the load balancer spreads requests, the symptom is
intermittent authorisation failures on roughly one request in six — which reads as a load
balancer problem, not a configuration one. Check it before releasing traffic:

```bash
docker compose --env-file env/prod.env exec auth printenv PAYMENT_TOKEN_HMAC_SECRET | sha256sum
# same hash on all six servers, or stop and fix it
```

Validate the file before starting anything:

```bash
docker compose --env-file env/prod.env --profile app --profile worker config --quiet
```

### 6.3 Redis host first

Nothing else can serve a session without it.

```bash
cd payment-platform/docker
./docker/deploy.sh env/prod.env --redis      # refuses to start with an empty REDIS_PASSWORD
```

The same env file is used on this host as on the application servers, so `APP_VERSION`
must be set in it even though no application container runs here — Compose interpolates
the whole file whatever profile is active.

Prove the two settings from §1.2 actually took:

```bash
docker exec -it payment-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning \
  CONFIG GET maxmemory-policy
# -> noeviction.  Anything else and you are one memory spike from a silent security hole.

docker exec -it payment-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning \
  CONFIG GET appendonly
# -> yes
```

Open port 6379 from the six application servers to this host in the VCN security list,
and from nowhere else.

### 6.4 One application server, then verify, then the rest

Never all six at once. The first server is the one that finds the configuration mistake.

```bash
./docker/deploy.sh env/prod.env
```

That validates the configuration before stopping anything, prints the token-secret
fingerprint to compare across servers, pulls, recreates only what changed, waits for every
health check and refuses to report success if one does not go healthy. The equivalent by
hand:

```bash
docker compose --env-file env/prod.env --profile app --profile worker pull
docker compose --env-file env/prod.env --profile app --profile worker up -d
docker compose --env-file env/prod.env --profile app --profile worker ps
```

Wait for health — the `start-period` is 90s, so give it that:

```bash
watch -n 5 'docker ps --format "table {{.Names}}\t{{.Status}}"'
# every container: "healthy".  "unhealthy" means readiness is DOWN, which almost always
# means Oracle or Redis is unreachable from this container.
```

Then:

```bash
# Readiness, from the host
for p in 8081 8082 8083 8084 8085; do
  echo -n "$p: "; curl -s "http://$PRIVATE_IP:$p/actuator/health/readiness"; echo
done

# Metrics need the credential; an unauthenticated 200 here is a finding
curl -s -u "prometheus:$ACTUATOR_PASSWORD" \
  "http://$PRIVATE_IP:8081/actuator/prometheus" | head -5

# Which build is actually running
docker inspect payment-auth \
  --format '{{index .Config.Labels "org.opencontainers.image.version"}} {{index .Config.Labels "org.opencontainers.image.revision"}}'

# Redis reachable from inside a container, not just from the host
docker compose --env-file env/prod.env exec auth bash -c 'exec 3<>/dev/tcp/$REDIS_HOST/6379 && echo reachable'
```

Only then add the server to the load balancer pool, run one real journey through it
(handshake → verify-agreement → OTP → verify-otp), confirm the `LMS_API_LOG` rows carry
the correlation id, and repeat on the next server.

### 6.5 The worker count is a decision, not a default

Six workers all claiming from `PAYMENT_POSTING_QUEUE` is safe — `FOR UPDATE SKIP LOCKED`
gives each disjoint rows, and the shared `group.id=payment-posting` stops Kafka
duplicating. What is **not** automatically safe is the rate: the agreed LMS ceiling
(300 postings/min now, 600 later) is a platform total, and six workers each running their
own batch rate will breach a ceiling that two workers respect.

Pick one before go-live:

- divide the target by the number of workers you run, or
- share the Redis token bucket at `rl:lms-posting` (see `payment-worker/README.md`).

If you choose fewer workers, simply omit `--profile worker` on the servers that should
not run one. The same applies to `LMS_MAX_CONCURRENT`, which is per service instance:
`6 servers × 4 LMS-calling services × 100 = 2400` concurrent calls, which is almost
certainly more than the LMS agreed to. Divide the agreed figure; do not copy the
development value. Getting this wrong does not fail on our side — it fails as a throttle
on theirs, during month-end peak.

---

## 7. Redeploy: no, a change in one service does not restart the other four

The earlier version of this document said "one tag for all five, released together." That
was right about **building** and wrong about **deploying**, and the two had been
conflated. Setting them straight:

**Build together.** All five come out of one Maven reactor at one version, because they
share `payment-shared`. You cannot build `payment-auth` against one version of the shared
contract and `payment-fetch` against another and call it a release.

**Deploy only what changed.** Each service has its own version variable in the env file,
defaulting to `APP_VERSION`. Override one and only that container is recreated — the
other four keep serving, untouched:

```ini
APP_VERSION=1.0.0        # what the platform is on
AUTH_VERSION=1.0.1       # auth alone moves; the rest stay on 1.0.0
```

```bash
./docker/deploy.sh env/prod.env auth
```

`up -d --no-deps auth` touches `payment-auth` and nothing else. No restart for fetch,
service, posting or worker.

### The one case where all five must move

When **`payment-shared` or the parent `pom.xml`** changes. `payment-shared` is not a
convenience library — it owns the payment token format, the Redis key layout, the session
contract and the crypto. Two services on different versions of it is a protocol mismatch,
and the way it surfaces is a token minted by one being rejected by another, intermittently,
on whichever requests the load balancer happens to route across the version boundary. That
reads as a load balancer fault, which is the most expensive kind of bug to chase.

You do not have to work this out by hand:

```bash
./docker/plan-release.sh v1.0.0 1.0.1
```

It diffs against the last release tag, maps the changed files to modules, applies the
`payment-shared` rule, and prints the exact build command, the env-file lines, and the
deploy command. Three possible answers: one or more named services, all five with the
reason, or nothing (when only docs or compose config changed).

### Full sequence for a one-service fix

```bash
# 1. what actually changed
./docker/plan-release.sh v1.0.0 1.0.1

# 2. build the jars (all of them - the reactor is one build) but only the image you need
mvn clean install
./docker/build-images.sh 1.0.1 registry.internal.tvscredit.co.in/payment --only auth --push

# 3. on each server, in turn, with that server out of the load balancer pool
#    set AUTH_VERSION=1.0.1 in env/prod.env, leave APP_VERSION alone, then:
./docker/deploy.sh env/prod.env auth
```

`deploy.sh` validates the configuration before stopping anything, prints the token-secret
fingerprint so you can compare it across servers, pulls, recreates only the named service,
waits for the health check, and refuses to declare success if it does not go healthy.

### When the change is configuration, not code

An env file or compose change needs no image build at all. `up -d` recreates only the
containers whose configuration actually changed:

```bash
docker compose --env-file env/prod.env --profile app --profile worker up -d
```

### Rollback

The previous version number — which is the whole reason not to use `latest`:

```bash
# one service back
sed -i 's/^AUTH_VERSION=.*/AUTH_VERSION=1.0.0/' env/prod.env
./docker/deploy.sh env/prod.env auth

# the whole platform back
sed -i 's/^APP_VERSION=.*/APP_VERSION=1.0.0/' env/prod.env
./docker/deploy.sh env/prod.env
```

**Rolling back the application does not roll back Oracle.** The DDL under
`payment-auth/src/main/resources/db` is forward-only. A release that adds a column is safe
to roll back; one that changes a constraint is not. Check before assuming the version
number is enough.

Never `docker compose down -v` on the Redis host. The `-v` removes the named volume and
with it the AOF file — every active session, every cached upstream token, and the
posting rate-limit bucket.

## 8. Day-2

### Prometheus

Scrape the published management ports on each server's private IP, with the actuator
credential:

```yaml
scrape_configs:
  - job_name: payment-platform
    basic_auth:
      username: prometheus
      password: <ACTUATOR_PASSWORD>
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['10.0.1.11:8081','10.0.1.11:8082','10.0.1.11:8083','10.0.1.11:8084','10.0.1.11:8085']
        labels: { server: app-01 }
      # repeat per server
```

The four to alert on first:

| Metric | Why it is first |
|---|---|
| `resilience4j_bulkhead_available_concurrent_calls{name="lms"}` near zero | The LMS is degrading. This moves before anything customer-visible does. |
| `resilience4j_circuitbreaker_state{name="lms"}` = open | Journeys cannot start. |
| Redis `used_memory` against `maxmemory` | With `noeviction`, hitting it stops writes — and stops payments. |
| Posting queue depth / oldest `PENDING` row age | The drain rate is below the arrival rate, or the worker is dead. |

### Logs

Promtail or Alloy reads the json-file logs and ships to Loki. Keep labels
low-cardinality — `app`, `env`, `level`, `server`. `correlationId` is a **field**, not a
label: a per-request label is the standard way to destroy a Loki install.

Loki retention, in India, is what satisfies the 180-day obligation. The compose rotation
does not.

### Routine checks

```bash
docker stats --no-stream                     # memory against the limits
docker compose --env-file env/prod.env logs --tail=100 -f auth
docker system df                             # before /var/lib/docker fills
docker image prune -a --filter "until=720h"  # keep the last few tags for rollback
```

---

## 9. Failures you will actually hit

| Symptom | Cause | Fix |
|---|---|---|
| Container `unhealthy`, readiness DOWN | Oracle or Redis unreachable from the container | `exec` in and test `/dev/tcp/$REDIS_HOST/6379`; check the VCN security list, not the app |
| `PRIVATE_IP must be set` | `--env-file` not passed, or the variable is missing | Pass `--env-file env/prod.env` on every compose command |
| Prometheus gets connection refused on 8081 | `MANAGEMENT_BIND_ADDRESS` left at `127.0.0.1` | Set it to `0.0.0.0`; the container boundary is the control (§1.3) |
| Oracle auth fails with a correct password | `$` in the password eaten by Compose | Double it: `$$` |
| Partner token rejected, no useful error | `$` in the bcrypt hash eaten by Compose | Double every `$` in the hash |
| Authorisation fails on ~1 request in 6 | `PAYMENT_TOKEN_HMAC_SECRET` differs on one server | Compare the sha256 on all six (§6.2) |
| All sessions drop at once, no deployment | Redis restarted, or evicted keys | Check `maxmemory-policy` is `noeviction` and `appendonly` is `yes` |
| Burst of LMS token requests from all servers | Redis restarted without AOF, cache empty | Enable AOF; do not run Redis on an app host |
| LMS throttles us at month-end | `LMS_MAX_CONCURRENT` × services × servers above the agreed figure | Divide the agreed total (§6.5) |
| `docker build` fails on `jarmode=tools` | Spring Boot version does not support it | Use the single-layer form commented in `docker/Dockerfile` |
| Jar not found during image build | `mvn clean install` not run, or run inside a module | Build from the repository root |
| Anti-replay rejects valid requests intermittently | Host clock drift on one server | NTP to NIC/NPL on every host |
| Dev: connection refused to Redis from a container | `REDIS_HOST=localhost` — that is the container's loopback | `host.docker.internal:6363`, or the container name with port **6379** (§1.5) |
| `required variable APP_VERSION is missing` on the Redis host | Compose interpolates the whole file regardless of profile | Set `APP_VERSION` in the Redis host's env file too |
| A one-line fix restarted all five services | `APP_VERSION` bumped instead of the one `*_VERSION` | `./docker/plan-release.sh`, then set only the service's own version line (§7) |

---

## 10. Files

```
payment-platform/
├── .dockerignore                 keeps secrets and stale jars out of image layers
├── docker/
│   ├── Dockerfile                one definition, all five services (ARG SERVICE)
│   ├── Dockerfile.builder        optional containerised jar build
│   ├── docker-compose.yml        profiles: redis | app | worker; per-service versions
│   ├── plan-release.sh           which services a change actually affects
│   ├── build-images.sh           images, labelled with the git sha; --only for a subset
│   ├── deploy.sh                 recreate named services, wait for healthy, abort if not
│   ├── healthcheck.sh            readiness probe with no added packages
│   ├── redis/redis.conf          noeviction + AOF. Read §1.2.
│   └── env/
│       ├── dev.env.example
│       ├── uat.env.example
│       └── prod.env.example
```

Quick reference:

```bash
# development - uses your existing local Redis on 6363, starts no Redis of its own
docker compose --env-file env/dev.env --profile app --profile worker up -d

# Redis host (UAT / production)
./docker/deploy.sh env/prod.env --redis

# application server, everything
./docker/deploy.sh env/prod.env

# application server, one service only
./docker/deploy.sh env/prod.env auth

# what does this change actually need redeployed?
./docker/plan-release.sh v1.0.0 1.0.1
```
