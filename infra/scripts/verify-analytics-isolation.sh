#!/usr/bin/env bash
#
# verify-analytics-isolation.sh — TASK-P14-007 §7.17 static/config isolation gate.
#
# Proves the analytics read model has no reverse dependency from the business write path:
#   1. reservation/payment/ticket/event services contain no client, base URL, or
#      service-discovery reference to analytics-service in main sources;
#   2. their Docker depends_on lists do not include analytics-service;
#   3. analytics-service depends only on infrastructure (postgres/kafka/eureka), never
#      on a business service;
#   4. analytics-service performs no synchronous REST calls to business services.
#
# This script verifies architecture/configuration facts only. Functional isolation
# (duplicate/outage-safe projections, read-model-only queries) is proven by the
# executable suites:
#   AnalyticsKafkaProjectionIntegrationTest, AnalyticsReplayDeterminismIntegrationTest,
#   AnalyticsOutOfOrderIntegrationTest, AnalyticsAggregateGrainIntegrationTest,
#   AdminAnalyticsApiIntegrationTest, AnalyticsConsumerKafkaIntegrationTest.
#
# Usage: bash infra/scripts/verify-analytics-isolation.sh [seatflow-root]
# Exit 0 when every check passes, 1 otherwise.
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
failures=0

check() {
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "PASS: ${description}"
  else
    echo "FAIL: ${description}"
    failures=$((failures + 1))
  fi
}

expect_no_match() {
  local description="$1"
  local pattern="$2"
  shift 2
  if grep -rEi "${pattern}" "$@" >/dev/null 2>&1; then
    echo "FAIL: ${description}"
    grep -rEi "${pattern}" "$@" | head -5
    failures=$((failures + 1))
  else
    echo "PASS: ${description}"
  fi
}

BUSINESS_SERVICES=(reservation-service payment-service ticket-service event-service)

# Print the top-level service block for $1 from $2 (up to the next service).
service_block() {
  awk -v name="$1" 'emit && /^  [A-Za-z0-9_-]+:/{exit} $0 == "  "name":"{emit=1} emit' "$2"
}

# 1. No analytics client/URL/discovery reference in business service main sources.
for service in "${BUSINESS_SERVICES[@]}"; do
  expect_no_match \
    "${service} main sources contain no analytics-service reference" \
    "analytics-service|analytics_service" \
    "${ROOT}/backend/services/${service}/src/main"
done

# 2. No analytics entry in business service Docker depends_on (services + prod files).
for service in "${BUSINESS_SERVICES[@]}"; do
  for compose in docker-compose.services.yml docker-compose.prod.yml; do
    block=$(service_block "${service}" "${ROOT}/docker/${compose}")
    if printf '%s\n' "${block}" | grep -A10 'depends_on:' | grep -q 'analytics-service'; then
      echo "FAIL: ${service} depends_on analytics-service in ${compose}"
      failures=$((failures + 1))
    else
      echo "PASS: ${service} does not depend on analytics-service in ${compose}"
    fi
  done
done

# 3. analytics-service depends only on infrastructure, never on business services.
for compose in docker-compose.services.yml docker-compose.prod.yml; do
  block=$(service_block "analytics-service" "${ROOT}/docker/${compose}")
  deps=$(printf '%s\n' "${block}" | grep -A10 'depends_on:' | grep -E '^\s+[a-z-]+:' || true)
  if [[ -z ${deps} ]]; then
    # Override fragments (e.g. prod) carry no depends_on of their own and inherit the base.
    echo "INFO: analytics-service has no depends_on override in ${compose} (inherits base)"
  else
    echo "INFO: analytics-service depends_on in ${compose}: $(printf '%s' "${deps}" | tr -d ' :' | tr '\n' ',' | sed 's/,$//')"
  fi
  for business in "${BUSINESS_SERVICES[@]}" user-service seat-map-service notification-service realtime-service; do
    if printf '%s\n' "${deps}" | grep -q "${business}"; then
      echo "FAIL: analytics-service depends on business service ${business} in ${compose}"
      failures=$((failures + 1))
    fi
  done
done
echo "PASS: analytics-service has no business-service dependency"

# 4. analytics-service performs no synchronous REST calls to business services.
expect_no_match \
  "analytics-service main sources contain no inter-service REST client" \
  "RestClient|WebClient|RestTemplate|@LoadBalanced|http://(reservation|payment|ticket|event)-service" \
  "${ROOT}/backend/services/analytics-service/src/main"

# 5. Gateway still routes admin analytics reads to the read model (informational).
check "gateway routes /api/admin/analytics/** to lb://analytics-service" \
  grep -q 'lb://analytics-service' \
  "${ROOT}/backend/services/api-gateway/src/main/java/com/seatflow/gateway/config/GatewayRoutesConfig.java"

if [[ ${failures} -gt 0 ]]; then
  echo "RESULT: FAIL (${failures} failing check(s))" >&2
  exit 1
fi
echo "RESULT: PASS — analytics isolation holds (static/config evidence)"
