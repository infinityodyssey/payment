# payment-platform

Five request-serving services plus one worker. Spring Boot 4 / Java 25 / Oracle / Redis /
Kafka.

```
payment-shared     contract between services — token, session key, crypto, LMS, ledgers
payment-auth       COMPLETE through OTP verification
payment-fetch      skeleton
payment-service    skeleton
payment-posting    skeleton
payment-worker     skeleton
```

## The rule that makes the split safe

**Only `auth-service` writes session state.** Every other service verifies a token
signature and reads one Redis key.

The token signs `sessionId` and `agreementNumber` rather than carrying them, so
verification recomputes the HMAC from the session the request arrived on and the
agreement being operated on. A token from another session, or presented against another
agreement, fails the signature — no lookup, no shared state.

The AES key cannot live in the token (that would transmit the key alongside the data it
protects), so it sits under its own Redis key. One read, no state access. That read is
the entire coupling between auth and everything downstream, and it belongs to
`payment-shared`.

## One transport for every upstream call

`UpstreamHttpClient` in `payment-shared` is the only class in the platform that performs
an outbound HTTP request. A caller describes the call as an `UpstreamCall` — upstream
name, API name, URL, headers, body, audit context — and the transport owns the
connection, the bulkhead, the circuit breaker, the timing, the `LMS_API_LOG` write and
the translation of every failure into one controlled error.

`LmsApiClient` holds every LMS API: token, agreement fetch, OTS amount, status check,
post transaction. One class, one header-assembly method, one token path. The next LMS
endpoint is one method, not one more client class.

Resilience is applied programmatically rather than with `@Bulkhead` / `@CircuitBreaker`.
With the APIs consolidated, a business method calls its own token method on `this`, and
an AOP proxy is bypassed on self-invocation — the token call would have been left with no
bulkhead and no breaker. The transport is a separate bean, so the decoration holds
whoever called whom; and because the upstream name is read per call rather than baked
into an annotation, one transport serves `lms` and `sms` with their own limits.

The upstream name resolves three things that must stay in step: the RestClient bean
(`<name>RestClient`), `resilience4j.bulkhead.instances.<name>` and
`resilience4j.circuitbreaker.instances.<name>`.

## Building

```bash
mvn clean install            # shared must install before the services resolve it
mvn -pl payment-auth test    # token and crypto suites, no infrastructure needed
```

Six modules, **five runnable jars**. `payment-shared` has no `spring-boot-maven-plugin`
and no main class — it is a library installed to the local repository so the other five
resolve it. `java -jar` on it fails.

## Running in Docker

```bash
mvn clean install                                # 5 runnable jars
./docker/build-images.sh 1.0.0                   # 5 images (or --only auth fetch)
cd docker && cp env/dev.env.example env/dev.env  # then fill it in
docker compose --env-file env/dev.env --profile app --profile worker up -d
```

Development uses the Redis already running on your machine, so there is no `redis`
profile in that command. From a container, that Redis is `host.docker.internal:6363` —
`localhost` inside a container is the container's own loopback and reaches nothing. From
the IDE it is `localhost:6363`, which is the default in `application-dev.properties`, so
running a service from the IDE needs no environment variables for Redis at all.

One `docker/Dockerfile` for all five services, selected by `--build-arg SERVICE=`. One
`docker-compose.yml` with profiles `redis`, `app` and `worker`, so the same file serves a
laptop and the six OCI servers. UAT and production run Redis from
`docker/redis/redis.conf`.

**A change in one service does not restart the other four.** Each has its own version
variable; override one and only that container is recreated:

```bash
./docker/plan-release.sh v1.0.0 1.0.1    # which services does this change actually affect?
./docker/deploy.sh env/prod.env auth     # recreate auth only, wait for healthy
```

The one exception is `payment-shared`: when it changes, all five move together, because it
owns the token format and the Redis key layout and a mixed set is a protocol mismatch.
`plan-release.sh` enforces that for you.

Full runbook, including the three Docker-specific traps that fail quietly, in
`architecture/DOCKER_DEPLOYMENT.md`. The shortest version of those three:

- **Redis does not run on an application server.** A deployment there logs out every
  borrower on all six servers and empties the upstream token cache.
- **`maxmemory-policy noeviction` is a security setting.** Any eviction policy silently
  deletes anti-replay nonces and live session keys under memory pressure.
- **`MANAGEMENT_BIND_ADDRESS=0.0.0.0` inside a container.** The properties default to
  `127.0.0.1`, which is right on metal and makes metrics unreachable in a container.

`payment-shared` is load-bearing infrastructure, not a convenience library. Changing the
session key layout or the token format is a coordinated release across every service.
Version it properly.

## Read before extending

Each skeleton module has a README with what belongs in it, what must not, and the wiring
still needed. Two of those notes are the critical findings from the legacy review:

- **Gateway webhook signature verification** (`payment-service`). The legacy
  `verifyGatewaySignature` returned `true` and its result was ignored — a forgeable
  payment success reachable by anyone who can guess a transaction id.
- **Server-side amount validation** (`payment-service`). Both legacy backends took the
  amount from the client with only a minimum check.

`payment-worker` holds every duplicate-posting control. Its README is the shortest path
to understanding why `SKIP LOCKED`, the unique constraints and the shared `group.id` are
not optional.

## Not done

Nothing here has been compiled, load-tested or penetration-tested. The four skeletons are
application classes and configuration only.
