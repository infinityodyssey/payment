#!/bin/bash
#
# Readiness probe with nothing added to the image.
#
# curl and wget are not installed on purpose. Every package in a payment-application
# image is a line item in the next vulnerability assessment, and a HTTP client in the
# runtime image is also a tool an attacker can use to reach the LMS or exfiltrate. bash's
# /dev/tcp needs no package.
#
# Readiness rather than liveness: the container process can be perfectly alive while
# Oracle or Redis are unreachable, and in that state it must not be sent borrower
# traffic. /actuator/health is permitAll in ManagementSecurityConfig, so this carries no
# credential - a probe that holds one is a credential sitting in `docker inspect`.

set -u

PORT="${MANAGEMENT_PORT:-8081}"
PATH_="/actuator/health/readiness"

# 3<> opens a read/write socket on fd 3. A refused connection or an unresolvable host
# fails here, which is the correct answer during startup.
exec 3<>"/dev/tcp/127.0.0.1/${PORT}" || exit 1

printf 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAccept: application/json\r\n\r\n' \
    "${PATH_}" >&3 || exit 1

# Spring returns {"status":"UP"} for the readiness group. show-details=never, so there is
# nothing else in the body to match on - and nothing in it to leak.
if grep -q '"status":"UP"' <&3; then
    exit 0
fi

exit 1
