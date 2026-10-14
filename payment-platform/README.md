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

## Running a jar directly

For validating a build against a real Oracle, with no Docker. Spring Boot reads
`./config/` next to the working directory automatically:

```
validation/
  payment-auth-1.0.0-SNAPSHOT.jar
  config/application-local.properties      <- from config/*.example, git-ignored
```

```bash
cd validation && java -jar payment-auth-*.jar --spring.profiles.active=dev,local
```

A file rather than `export ORACLE_PASSWORD=...`: an exported variable lands in shell
history and in `/proc`, and vanishes next session, so it ends up pasted into a script that
gets committed. A file travels with the jar and `chmod 600` works on it. It is not a
secrets manager — production credentials belong in OCI Vault or a root-owned env file.

## Running in Docker

```bash
mvn clean install                                # 5 runnable jars
./docker/build-images.sh 1.0.0                   # 5 images (or --only auth fetch)
cd docker && cp env/dev.env.example env/dev.env
docker compose --env-file env/dev.env --profile dev --profile app --profile worker up -d
```

`--profile dev` starts a Redis for local development, published to `127.0.0.1:6363`.
UAT and production use the Redis the infra team runs — nothing here starts one there, and
`docker/redis-dev.conf`'s header is the brief for whoever sets it up.

Containers reach the dev Redis at `redis:6379`; your IDE reaches it at `localhost:6363`,
which is already the default in `application-dev.properties`. 6379 is the container port,
6363 the host port, and `localhost` inside a container is that container's own loopback.

**A change in one service does not restart the other four.** Each has its own version
variable:

```ini
APP_VERSION=1.0.0
AUTH_VERSION=1.0.1     # auth alone moves
```

The exception is `payment-shared`: when it changes, all five move together, because it
owns the token format and the Redis key layout and a mixed set is a protocol mismatch.

Full runbook: `architecture/DOCKER_DEPLOYMENT.md`.

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
