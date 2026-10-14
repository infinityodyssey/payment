#!/usr/bin/env bash
#
# Deploys to THIS server. Recreates only the services you name, waits for each to report
# healthy, and stops at the first one that does not.
#
#   ./docker/deploy.sh env/prod.env                       # all of them
#   ./docker/deploy.sh env/prod.env auth                  # only auth-service
#   ./docker/deploy.sh env/prod.env fetch posting         # two of them
#   ./docker/deploy.sh env/prod.env --redis               # the Redis host
#
# Run it from the docker/ directory, with this server already OUT of the load balancer
# pool. It does not drain traffic for you - that is the load balancer's job and it is the
# one step that must happen before this script, not during it.

set -euo pipefail

ENV_FILE_ARG="${1:-}"
shift || true

if [[ -z "$ENV_FILE_ARG" ]]; then
  echo "usage: $0 <env-file> [service ...] [--redis]" >&2
  echo "   eg: $0 env/prod.env auth" >&2
  exit 2
fi
[[ -f "$ENV_FILE_ARG" ]] || { echo "error: no such env file: $ENV_FILE_ARG" >&2; exit 2; }
[[ -f docker-compose.yml ]] || { echo "error: run this from the docker/ directory" >&2; exit 2; }

REDIS_ONLY=false
SERVICES=()
for arg in "$@"; do
  case "$arg" in
    --redis) REDIS_ONLY=true ;;
    *)       SERVICES+=("$arg") ;;
  esac
done

COMPOSE=(docker compose --env-file "$ENV_FILE_ARG")

if [[ "$REDIS_ONLY" == true ]]; then
  PROFILES=(--profile redis)
  TARGETS=(redis)
  # Checked here as well as in the container, because catching it before anything stops is
  # cheaper than catching it in a restart loop. An unauthenticated Redis holding live AES
  # session keys is found by scanners in minutes.
  if ! grep -qE '^REDIS_PASSWORD=.+' "$ENV_FILE_ARG"; then
    echo "error: REDIS_PASSWORD is empty in ${ENV_FILE_ARG}." >&2
    echo "       Generate one: openssl rand -base64 32" >&2
    echo "       Changing it requires restarting all five services on all six servers," >&2
    echo "       so set it before the first start, not after." >&2
    exit 2
  fi
else
  PROFILES=(--profile app --profile worker)
  TARGETS=("${SERVICES[@]}")
fi

echo "==> Validating configuration"
# Catches a missing PRIVATE_IP, a typo in a variable name and an unresolvable image tag
# before anything is stopped, rather than halfway through.
"${COMPOSE[@]}" "${PROFILES[@]}" config --quiet

# The check that saves the most time later. A server whose token secret differs from the
# others rejects every token issued elsewhere; because the load balancer spreads requests,
# that presents as intermittent authorisation failures rather than a config error. Compare
# this hash across all six servers before releasing traffic to any of them.
if [[ "$REDIS_ONLY" == false ]]; then
  SECRET_HASH="$(grep -E '^PAYMENT_TOKEN_HMAC_SECRET=' "$ENV_FILE_ARG" | cut -d= -f2- | sha256sum | cut -c1-16)"
  echo "    token secret fingerprint: ${SECRET_HASH}   <- must match on every server"
fi

echo "==> Pulling images"
if [[ ${#TARGETS[@]} -gt 0 ]]; then
  "${COMPOSE[@]}" "${PROFILES[@]}" pull "${TARGETS[@]}"
else
  "${COMPOSE[@]}" "${PROFILES[@]}" pull
fi

echo "==> Recreating"
# Without a service list, `up -d` recreates only containers whose image or configuration
# changed and leaves the rest running. With one, only those are touched at all.
if [[ ${#TARGETS[@]} -gt 0 ]]; then
  echo "    services: ${TARGETS[*]}"
  "${COMPOSE[@]}" "${PROFILES[@]}" up -d --no-deps "${TARGETS[@]}"
else
  echo "    services: every changed service"
  "${COMPOSE[@]}" "${PROFILES[@]}" up -d
fi

echo "==> Waiting for health"
# The image sets start-period=90s, so a service still 'starting' after that is genuinely
# failing readiness - almost always Oracle or Redis unreachable from the container.
DEADLINE=$(( $(date +%s) + 180 ))
mapfile -t CONTAINERS < <("${COMPOSE[@]}" "${PROFILES[@]}" ps --format '{{.Name}}' \
  $( [[ ${#TARGETS[@]} -gt 0 ]] && printf '%s ' "${TARGETS[@]}" ) 2>/dev/null)

FAILED=()
for c in "${CONTAINERS[@]}"; do
  [[ -n "$c" ]] || continue
  printf '    %-24s ' "$c"
  while :; do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null || echo gone)"
    case "$status" in
      healthy) echo "healthy"; break ;;
      none)    echo "no healthcheck (running)"; break ;;
      gone)    echo "CONTAINER GONE"; FAILED+=("$c"); break ;;
      unhealthy)
        echo "UNHEALTHY"
        FAILED+=("$c")
        break ;;
    esac
    if (( $(date +%s) > DEADLINE )); then
      echo "TIMEOUT (still ${status})"
      FAILED+=("$c")
      break
    fi
    sleep 5
  done
done

if [[ ${#FAILED[@]} -gt 0 ]]; then
  cat >&2 <<EOF

DEPLOY FAILED: ${FAILED[*]}

Do NOT return this server to the load balancer pool. Look at these in order:

  ${COMPOSE[*]} logs --tail=80 ${FAILED[0]#payment-}

  # readiness almost always fails on a dependency, not on the app
  ${COMPOSE[*]} exec ${FAILED[0]#payment-} bash -c 'exec 3<>/dev/tcp/\$REDIS_HOST/\$REDIS_PORT && echo redis reachable'

To go back to the previous build, set the version line in ${ENV_FILE_ARG} back and rerun
this script with the same arguments. Remember that Oracle DDL is forward-only - an
application rollback does not undo a schema change.
EOF
  exit 1
fi

echo
echo "==> Running on this server"
"${COMPOSE[@]}" "${PROFILES[@]}" ps --format 'table {{.Name}}\t{{.Image}}\t{{.Status}}'

cat <<EOF

Before returning this server to the load balancer pool:
  - run one real journey through it: handshake -> verify-agreement -> OTP -> verify-otp
  - confirm the LMS_API_LOG rows for that journey carry the correlation id
  - compare the token secret fingerprint above against the other servers

Then deploy the next server. Never all six at once; the first one is what finds the
configuration mistake.
EOF
