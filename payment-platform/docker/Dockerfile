# syntax=docker/dockerfile:1.7
#
# One image definition for all five bootable services.
#
# They are the same Spring Boot application shape with different code, so a Dockerfile
# each would be five copies of the same hardening decisions - and five places for one of
# them to drift. The service is chosen by a build argument:
#
#   docker build -f docker/Dockerfile --build-arg SERVICE=payment-auth -t pay/auth:1.0.0 .
#
# Build the jars first (mvn clean package). This image does not compile; see
# docker/Dockerfile.builder for the containerised build if the host has no JDK 25.

# Pin by digest before you go to production. A floating tag means the image that passed
# your VA scan is not necessarily the image that runs three months later, and "we scanned
# it" stops being a true statement. Get the digest with:
#   docker buildx imagetools inspect eclipse-temurin:25-jre-noble
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre-noble


# ---------------------------------------------------------------------------- layers
# Splits the fat jar into four layers that change at different rates. The five services
# share the same Spring, Oracle driver and Lettuce dependencies, so the dependency layer
# is built once and reused by all five images - and on redeploy only the application
# layer, a few hundred kilobytes, moves to the six servers instead of 250MB each.
FROM ${RUNTIME_IMAGE} AS layers

ARG SERVICE
WORKDIR /layers

# Exactly one jar. The *.original that spring-boot-maven-plugin leaves behind is excluded
# in .dockerignore - copying it would shadow the repackaged jar.
COPY ${SERVICE}/target/${SERVICE}-*.jar application.jar

RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# If your Spring Boot version rejects jarmode=tools, drop this stage and use the single
# layer form in the runtime stage instead:
#   COPY ${SERVICE}/target/${SERVICE}-*.jar /app/application.jar
#   ENTRYPOINT [... "-jar", "/app/application.jar"]
# You lose the layer sharing, nothing else.


# --------------------------------------------------------------------------- runtime
FROM ${RUNTIME_IMAGE}

ARG SERVICE
ARG APP_VERSION=0.0.0-dev
ARG BUILD_TIME=unknown
ARG GIT_SHA=unknown

# These end up in `docker inspect` and are the only reliable way to answer "which build
# is actually running on server 4" during an incident. An image tag can be retagged; a
# label baked at build time cannot.
LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.vendor="TVS Credit" \
      org.opencontainers.image.description="EMI repayment platform - ${SERVICE}"

# Asia/Kolkata in the container so timestamps in stdout logs match the audit rows and the
# LMS business date. This does NOT fix the clock: a container reads the host clock, so the
# host is what must be NTP-synced (NIC/NPL for an Indian regulated entity). An unsynced
# host silently breaks the anti-replay window, which rejects valid requests as stale.
ENV TZ=Asia/Kolkata \
    LANG=C.UTF-8 \
    SERVICE_NAME=${SERVICE}

# ---- unprivileged user ----
# A compromised application process must not be able to write to the image or install
# anything. Fixed uid/gid so a bind-mounted volume has predictable ownership.
RUN groupadd --gid 10001 payment \
 && useradd --uid 10001 --gid payment --no-create-home --shell /usr/sbin/nologin payment \
 && mkdir -p /app \
 && chown -R payment:payment /app

WORKDIR /app

# Order matters: least-changing layer first, so a code-only redeploy reuses the three
# layers above it.
COPY --from=layers --chown=payment:payment /layers/extracted/dependencies/ ./
COPY --from=layers --chown=payment:payment /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=payment:payment /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=payment:payment /layers/extracted/application/ ./

COPY --chown=payment:payment docker/healthcheck.sh /app/healthcheck.sh
RUN chmod 0555 /app/healthcheck.sh

USER payment:payment

# JVM settings that are decisions, not defaults:
#
#   MaxRAMPercentage=70        The JVM reads the cgroup limit, so this is a percentage of
#                              the container memory limit, not of the host. 70 leaves room
#                              for metaspace, thread stacks, Netty direct buffers (Lettuce)
#                              and the JDK HttpClient - all of which live outside the heap
#                              and are what actually gets the container OOM-killed.
#
#   ExitOnOutOfMemoryError     Die, let Docker restart it. A JVM that survives an OOM
#                              limps: requests fail at random, the circuit breakers see
#                              "the LMS is fine", and nothing in the platform reports a
#                              problem. A dead container is visible.
#
#   NO HeapDumpOnOutOfMemoryError, deliberately.
#                              A heap dump from this application contains live AES session
#                              keys, OTPs in flight, borrower mobile numbers and the token
#                              HMAC secret. Writing that to a shared volume creates a
#                              payment-credential disclosure path that no log filter
#                              covers. If you need dumps for a specific investigation,
#                              enable them for that window, on an encrypted volume, with
#                              the retention agreed in writing.
#
#   java.io.tmpdir=/tmp        Tomcat needs a writable temp directory. The compose file
#                              runs the container read-only with /tmp as tmpfs, so temp
#                              files live in RAM and vanish with the container.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 \
-XX:InitialRAMPercentage=40.0 \
-XX:+ExitOnOutOfMemoryError \
-XX:-HeapDumpOnOutOfMemoryError \
-XX:+UseG1GC \
-Djava.io.tmpdir=/tmp"

# Readiness, not liveness: a container that is up but cannot reach Oracle or Redis must
# not be sent traffic. /actuator/health is permitAll in ManagementSecurityConfig, so this
# needs no credential - which is the point, a probe should not hold one.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
    CMD ["/app/healthcheck.sh"]

# `sh -c` is here only so $JAVA_OPTS expands, and `exec` is what makes it safe: the shell
# replaces itself with the JVM, so the JVM is PID 1 and receives SIGTERM directly and
# Spring's graceful shutdown runs. Drop the `exec` and SIGTERM goes to the shell, the JVM
# is SIGKILLed when the stop timeout expires, and an in-flight LMS posting is cut
# mid-call - the exact state that needs a status check to resolve.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
