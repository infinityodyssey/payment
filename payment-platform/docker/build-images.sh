#!/usr/bin/env bash
#
# Builds service images from already-built jars. Run from the repository root.
#
#   mvn clean install
#   ./docker/build-images.sh 1.0.0
#   ./docker/build-images.sh 1.0.1 registry.internal/payment --only auth --push
#
# --only builds a subset, so a fix in one service does not produce five new images and
# five restarts. The exception: if payment-shared or the parent pom changed, build all
# five — it owns the token format and the Redis key layout, and a mixed set means a token
# minted by one service is rejected by another.

set -euo pipefail

SERVICES=(payment-auth payment-fetch payment-service payment-posting payment-worker)
PUSH=false
ONLY=()
ARGS=()
MODE=positional

for arg in "$@"; do
  case "$arg" in
    --push) PUSH=true; MODE=positional ;;
    --only) MODE=only ;;
    *)      [[ "$MODE" == only ]] && ONLY+=("${arg#payment-}") || ARGS+=("$arg") ;;
  esac
done

VERSION="${ARGS[0]:-}"
REGISTRY="${ARGS[1]:-payment}"

[[ -n "$VERSION" ]] || { echo "usage: $0 <version> [registry] [--only svc ...] [--push]" >&2; exit 2; }
[[ -f pom.xml ]]    || { echo "error: run from the repository root" >&2; exit 2; }

if [[ ${#ONLY[@]} -gt 0 ]]; then
  SELECTED=()
  for want in "${ONLY[@]}"; do
    [[ " ${SERVICES[*]} " == *" payment-${want} "* ]] \
      || { echo "error: '${want}' is not a service. One of: ${SERVICES[*]#payment-}" >&2; exit 2; }
    SELECTED+=("payment-${want}")
  done
  SERVICES=("${SELECTED[@]}")
fi

# Fail before building anything rather than four images in.
for svc in "${SERVICES[@]}"; do
  compgen -G "${svc}/target/${svc}-*.jar" > /dev/null \
    || { echo "error: no jar in ${svc}/target — run: mvn clean install" >&2; exit 1; }
done

GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

for svc in "${SERVICES[@]}"; do
  echo ">> ${REGISTRY}/${svc}:${VERSION}"
  docker build -f docker/Dockerfile \
    --build-arg "SERVICE=${svc}" \
    --build-arg "APP_VERSION=${VERSION}" \
    --build-arg "GIT_SHA=${GIT_SHA}" \
    -t "${REGISTRY}/${svc}:${VERSION}" .
  # No `latest` tag: a floating tag means a restart can silently change the running code.
  [[ "$PUSH" == true ]] && docker push "${REGISTRY}/${svc}:${VERSION}"
done

echo
echo "Built ${#SERVICES[@]} image(s) at ${VERSION} (git ${GIT_SHA}): ${SERVICES[*]#payment-}"
if [[ ${#SERVICES[@]} -eq 5 ]]; then
  echo "Set APP_VERSION=${VERSION} in each server's env file and clear any *_VERSION overrides."
else
  echo "Set only these lines in each server's env file; leave APP_VERSION alone:"
  for svc in "${SERVICES[@]}"; do
    printf '  %s_VERSION=%s\n' "$(tr '[:lower:]' '[:upper:]' <<< "${svc#payment-}")" "${VERSION}"
  done
fi
