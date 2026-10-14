# syntax=docker/dockerfile:1.7
#
# Optional: builds the five jars inside a container, so the build host needs Docker and
# nothing else - no JDK 25, no Maven, no "it compiled on my laptop".
#
# This does NOT produce a runnable image. It produces the jars, which docker/Dockerfile
# then packages. Use it when the OCI build server does not have JDK 25, or when you want
# the same compiler on every build.
#
#   docker build -f docker/Dockerfile.builder --target jars --output type=local,dest=. .
#
# The jars land in each module's target/ exactly as `mvn package` would leave them.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25

FROM ${MAVEN_IMAGE} AS build
WORKDIR /src

# Poms first, dependencies resolved, then sources. A source-only change then reuses the
# dependency download - which for this reactor is several hundred megabytes and the
# slowest part of a cold build.
COPY pom.xml .
COPY payment-shared/pom.xml   payment-shared/
COPY payment-auth/pom.xml     payment-auth/
COPY payment-fetch/pom.xml    payment-fetch/
COPY payment-service/pom.xml  payment-service/
COPY payment-posting/pom.xml  payment-posting/
COPY payment-worker/pom.xml   payment-worker/

# A cache mount, not a baked layer: the local repository stays out of the image, so no
# dependency jar and no settings.xml credential ends up in a pushed layer.
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -q dependency:go-offline -DskipTests || true

COPY payment-shared/src   payment-shared/src
COPY payment-auth/src     payment-auth/src
COPY payment-fetch/src    payment-fetch/src
COPY payment-service/src  payment-service/src
COPY payment-posting/src  payment-posting/src
COPY payment-worker/src   payment-worker/src

# Tests run here. Skipping them to get a release out is how the token and crypto suites
# stop being a safety net - they are the only automated check that a token issued for one
# session cannot be replayed into another.
ARG MAVEN_ARGS="-B -ntp"
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn ${MAVEN_ARGS} clean package


# Exports only the jars. `--output type=local` writes these paths to the host.
FROM scratch AS jars
COPY --from=build /src/payment-auth/target/payment-auth-*.jar        /payment-auth/target/
COPY --from=build /src/payment-fetch/target/payment-fetch-*.jar      /payment-fetch/target/
COPY --from=build /src/payment-service/target/payment-service-*.jar  /payment-service/target/
COPY --from=build /src/payment-posting/target/payment-posting-*.jar  /payment-posting/target/
COPY --from=build /src/payment-worker/target/payment-worker-*.jar    /payment-worker/target/
