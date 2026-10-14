#!/usr/bin/env bash
#
# Builds service images from already-built jars. All five by default, or a subset.
#
#   ./docker/build-images.sh 1.0.0
#   ./docker/build-images.sh 1.0.0 registry.internal.tvscredit.co.in/payment --push
#   ./docker/build-images.sh 1.0.1 --only auth fetch        # just these two
#
# Run from the repository root. Build the jars first:
#
#   mvn clean install          (or ./docker/build-images.sh --with-maven 1.0.0)
#
# --only builds a subset, so a fix in one service does not produce five new images and
# force five restarts. Use ./docker/plan-release.sh to work out the subset from the git
# diff rather than from memory - it also applies the one rule that overrides the subset:
# if payment-shared or the parent pom changed, all five move together, because
# payment-shared owns the token format and the Redis key layout and a mixed set is a
# protocol mismatch.

set -euo pipefail

SERVICES=(payment-auth payment-fetch payment-service payment-posting payment-worker)

WITH_MAVEN=false
PUSH=false
ONLY=()
ARGS=()
MODE=positional

for arg in "$@"; do
  case "$arg" in
    --with-maven) WITH_MAVEN=true; MODE=positional ;;
    --push)       PUSH=true;       MODE=positional ;;
    --only)       MODE=only ;;
    *)
      if [[ "$MODE" == only ]]; then
        # Accept both spellings: `auth` and `payment-auth`.
        ONLY+=("${arg#payment-}")
      else
        ARGS+=("$arg")
      fi
      ;;
  esac
done

VERSION="${ARGS[0]:-}"
REGISTRY="${ARGS[1]:-payment}"

if [[ -z "$VERSION" ]]; then
  echo "usage: $0 <version> [registry] [--with-maven] [--push] [--only svc ...]" >&2
  exit 2
fi

# Narrow to the requested subset, rejecting a name that is not a service rather than
# silently building nothing.
if [[ ${#ONLY[@]} -gt 0 ]]; then
  SELECTED=()
  for want in "${ONLY[@]}"; do
    match=""
    for svc in "${SERVICES[@]}"; do
      [[ "$svc" == "payment-${want}" ]] && match="$svc"
    done
    if [[ -z "$match" ]]; then
      echo "error: '${want}' is not a service. One of: ${SERVICES[*]#payment-}" >&2
      exit 2
    fi
    SELECTED+=("$match")
  done
  SERVICES=("${SELECTED[@]}")
fi

if [[ ! -f pom.xml ]]; then
  echo "error: run this from the repository root (no pom.xml here)" >&2
  exit 2
fi

# Recorded in the image labels. During an incident this is how you tell what is actually
# running on server 4, because a tag can be moved and a label cannot.
GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [[ "$WITH_MAVEN" == true ]]; then
  echo ">> mvn clean install"
  mvn -B -ntp clean install
fi

# Fail before building five images if a jar is missing, rather than four images in.
for svc in "${SERVICES[@]}"; do
  if ! compgen -G "${svc}/target/${svc}-*.jar" > /dev/null; then
    echo "error: no jar in ${svc}/target. Run: mvn clean install" >&2
    exit 1
  fi
done

for svc in "${SERVICES[@]}"; do
  tag="${REGISTRY}/${svc}:${VERSION}"

  echo ">> ${tag}"
  docker build \
    -f docker/Dockerfile \
    --build-arg "SERVICE=${svc}" \
    --build-arg "APP_VERSION=${VERSION}" \
    --build-arg "BUILD_TIME=${BUILD_TIME}" \
    --build-arg "GIT_SHA=${GIT_SHA}" \
    -t "${tag}" \
    .

  # No `latest`. A floating tag in a payment platform means a restart can silently change
  # the running code, and the image that passed the vulnerability assessment is not
  # necessarily the one serving borrowers.

  if [[ "$PUSH" == true ]]; then
    docker push "${tag}"
  fi
done

echo
echo "Built ${#SERVICES[@]} image(s) at ${VERSION} (git ${GIT_SHA}): ${SERVICES[*]#payment-}"
docker images --filter "reference=${REGISTRY}/payment-*:${VERSION}" \
  --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}'

echo
echo "Next:"
echo "  1. Scan before you ship:  docker scout cves ${REGISTRY}/${SERVICES[0]}:${VERSION}"
echo "     (or trivy image ...). A VA every six months is required; per build is cheaper."

if [[ ${#SERVICES[@]} -eq 5 ]]; then
  echo "  2. Set APP_VERSION=${VERSION} in each server's env file, and clear any"
  echo "     per-service *_VERSION overrides so all five follow it."
else
  echo "  2. Set ONLY these lines in each server's env file. Leave APP_VERSION alone -"
  echo "     it is the version the untouched services keep running:"
  for svc in "${SERVICES[@]}"; do
    printf '        %s_VERSION=%s\n' \
      "$(echo "${svc#payment-}" | tr '[:lower:]' '[:upper:]')" "${VERSION}"
  done
fi

printf '  3. Deploy one server at a time:  ./docker/deploy.sh env/prod.env'
for svc in "${SERVICES[@]}"; do printf ' %s' "${svc#payment-}"; done
echo
echo "     Full runbook: architecture/DOCKER_DEPLOYMENT.md"

