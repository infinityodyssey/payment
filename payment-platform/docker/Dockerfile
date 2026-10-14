# One image definition for all five services. Pick one with --build-arg SERVICE=.
#
#   docker build -f docker/Dockerfile --build-arg SERVICE=payment-auth -t payment/payment-auth:1.0.0 .
#
# Build the jars first: mvn clean install

ARG RUNTIME_IMAGE=eclipse-temurin:25-jre-noble
FROM ${RUNTIME_IMAGE}

ARG SERVICE
ARG APP_VERSION=dev
ARG GIT_SHA=unknown

# `docker inspect` is how you tell which build is on which server during an incident.
# A tag can be moved after the fact; a label cannot.
LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}"

# Container timestamps match the audit rows and the LMS business date. The clock itself
# comes from the host, so the host is what needs NTP.
ENV TZ=Asia/Kolkata

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin payment

WORKDIR /app
COPY --chown=payment ${SERVICE}/target/${SERVICE}-*.jar application.jar
USER payment

ENV MANAGEMENT_PORT=8081

# MaxRAMPercentage reads the container limit, not the host. 70 leaves room for metaspace,
# thread stacks and Netty direct buffers — the off-heap memory that gets a container
# OOM-killed.
#
# ExitOnOutOfMemoryError: die and let Docker restart. A JVM that survives an OOM limps —
# requests fail at random, the circuit breakers conclude the LMS is fine, and nothing
# reports a problem.
#
# No HeapDumpOnOutOfMemoryError: a dump here holds live AES session keys, OTPs in flight
# and the token HMAC secret. Turn it on per investigation, not by default.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

# Readiness, not liveness: a container can be alive while Oracle or Redis are unreachable,
# and in that state it must not get traffic. /actuator/health is permitAll, so no
# credential is needed here.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
  CMD curl -fsS "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health/readiness" || exit 1

# `exec` makes the JVM PID 1, so SIGTERM reaches it and Spring's graceful shutdown runs.
# Without it the JVM is killed at the stop timeout, cutting an in-flight LMS posting.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
