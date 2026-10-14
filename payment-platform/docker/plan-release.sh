#!/usr/bin/env bash
#
# Works out which services a change actually affects, so a one-service fix does not
# restart the other four.
#
#   ./docker/plan-release.sh v1.0.0            # against the last release tag
#   ./docker/plan-release.sh v1.0.0 1.0.1      # and name the new version
#
# Prints the services to rebuild, the env-file lines to change, and the deploy command.
# Changes nothing.
#
# THE ONE RULE THAT OVERRIDES EVERYTHING: if payment-shared or the parent pom changed,
# all five move together. payment-shared is not a convenience library - it owns the
# payment token format, the Redis key layout and the session contract. Two services
# running different versions of it is a protocol mismatch, and the way it shows up is a
# token minted by one being rejected by another, intermittently, under load.

set -euo pipefail

PREV="${1:-}"
NEW_VERSION="${2:-}"

if [[ -z "$PREV" ]]; then
  echo "usage: $0 <previous-git-ref> [new-version]" >&2
  echo "   eg: $0 v1.0.0 1.0.1" >&2
  exit 2
fi

[[ -f pom.xml ]] || { echo "error: run from the repository root" >&2; exit 2; }
git rev-parse --verify "$PREV" >/dev/null 2>&1 || {
  echo "error: '$PREV' is not a git ref this repository knows" >&2; exit 2; }

ALL=(payment-auth payment-fetch payment-service payment-posting payment-worker)
CHANGED_FILES="$(git diff --name-only "${PREV}"..HEAD)"

if [[ -z "$CHANGED_FILES" ]]; then
  echo "Nothing changed since ${PREV}. No deployment needed."
  exit 0
fi

# Anything that is a cross-service contract forces the full set.
FORCE_ALL=false
FORCE_REASON=""
while IFS= read -r f; do
  case "$f" in
    payment-shared/*)
      FORCE_ALL=true; FORCE_REASON="payment-shared changed" ;;
    pom.xml)
      FORCE_ALL=true; FORCE_REASON="the parent pom changed (dependency or Java version)" ;;
  esac
done <<< "$CHANGED_FILES"

SELECTED=()
if [[ "$FORCE_ALL" == true ]]; then
  SELECTED=("${ALL[@]}")
else
  for svc in "${ALL[@]}"; do
    if grep -q "^${svc}/" <<< "$CHANGED_FILES"; then
      SELECTED+=("$svc")
    fi
  done
fi

echo "Changes since ${PREV}:"
echo "$CHANGED_FILES" | sed 's/^/  /'
echo

if [[ "$FORCE_ALL" == true ]]; then
  cat <<EOF
ALL FIVE SERVICES - ${FORCE_REASON}.

Not negotiable. payment-shared carries the token format, the Redis key layout and the
session contract; a mixed set means a token issued by one service is rejected by another.
Because the load balancer spreads requests, that presents as intermittent authorisation
failures rather than as a version problem, which is the hardest kind to diagnose.
EOF
elif [[ ${#SELECTED[@]} -eq 0 ]]; then
  cat <<EOF
NO SERVICE NEEDS REDEPLOYING.

Files changed, but none of them are inside a service module or payment-shared -
documentation, compose configuration or scripts. If you changed docker-compose.yml or an
env file, apply it with \`docker compose ... up -d\` (which recreates only the containers
whose configuration changed) and skip the image build.
EOF
  exit 0
else
  echo "SERVICES TO REBUILD AND RESTART: ${SELECTED[*]}"
  echo
  UNTOUCHED=()
  for svc in "${ALL[@]}"; do
    [[ " ${SELECTED[*]} " == *" ${svc} "* ]] || UNTOUCHED+=("$svc")
  done
  echo "Left running, untouched: ${UNTOUCHED[*]}"
fi

echo
if [[ -z "$NEW_VERSION" ]]; then
  echo "Pass a new version as the second argument to get the exact commands."
  exit 0
fi

echo "─────────────────────────────────────────────────────────────────"
echo "1. Build only what changed:"
echo
echo "   mvn clean install"
echo "   ./docker/build-images.sh ${NEW_VERSION} <registry> --only ${SELECTED[*]#payment-}"
echo
echo "2. On each server, set these lines in the env file:"
echo
if [[ "$FORCE_ALL" == true ]]; then
  echo "   APP_VERSION=${NEW_VERSION}"
  echo "   # and clear any per-service overrides, so all five follow APP_VERSION"
  for svc in "${ALL[@]}"; do
    printf '   # %s_VERSION=\n' "$(echo "${svc#payment-}" | tr '[:lower:]' '[:upper:]')"
  done
else
  echo "   # leave APP_VERSION alone - it is what the untouched services keep running"
  for svc in "${SELECTED[@]}"; do
    printf '   %s_VERSION=%s\n' "$(echo "${svc#payment-}" | tr '[:lower:]' '[:upper:]')" "${NEW_VERSION}"
  done
fi
echo
echo "3. Deploy, one server at a time, out of the load balancer pool:"
echo
printf '   ./docker/deploy.sh env/prod.env'
for svc in "${SELECTED[@]}"; do printf ' %s' "${svc#payment-}"; done
echo
echo
echo "   Only the named containers are recreated. The rest keep serving."
echo "─────────────────────────────────────────────────────────────────"
