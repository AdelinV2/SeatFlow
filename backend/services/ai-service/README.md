# SeatFlow AI Service — Operations Runbook (Phase 15)

Customer-facing AI assistant over Groq's OpenAI-compatible API with controlled,
read-only domain tools and one explicit-confirmation reservation boundary.
`ai-service` has **no direct domain database access**; availability, pricing, and holds always
come from the authoritative Event/Reservation services.

Related: `ADR-014`, `.ai/tasks/phase-15-ai-assistant-mcp/000-phase-overview.md`,
`.ai/architecture/09-post-mvp-evolution.md` (section 7).

---

## 1. Local setup (developer machine)

The only mandatory manual provider setup:

1. Create/sign in to a Groq Cloud account and create an API key.
2. Copy `.env.example` to `.env` (never commit `.env`) and set the key outside Git:
   `GROQ_API_KEY=<secret>`.
3. Set `AI_ENABLED=true` (default `false`: the platform starts and books normally without AI).
4. Optional overrides: `GROQ_MODEL` (default `openai/gpt-oss-20b`), `GROQ_BASE_URL`,
   `GROQ_MAX_COMPLETION_TOKENS` (default `600`).
5. Start `ai-service` (port `8090`) or the full compose stack; confirm
   `GET /api/ai/status` reports `READY` for an authenticated user.

Default CI needs no key and no internet: provider/downstream calls are mocked in tests, and the
single live test is opt-in (see section 4).

## 2. Production / demo setup

- Add the key to the `groq-api-key` Secret Manager container through the existing production
  secret/env mechanism; `render-runtime-env.sh` maps it to `GROQ_API_KEY` without putting it in
  Terraform state or Git. **Never commit the key.** A populated secret enables AI by default;
  `AI_ENABLED=false` is an explicit fail-safe override.
- If the key is blank or invalid outside the deployment renderer, the service starts and reports
  `MISCONFIGURED` instead of failing startup, and core booking is unaffected.
- **Quick-disable without redeploying the platform:** rerender the runtime environment with
  `AI_ENABLED=false` and restart only `ai-service` (or remove the Secret Manager version before
  the next release). The Angular drawer then shows the disabled state; event browsing, holds, and
  checkout keep working.
- **Free-tier quota exhausted:** users see "The assistant is temporarily busy. Please wait a
  moment and try again. The rest of SeatFlow works normally." No quota internals, account
  metadata, or retry storms are exposed; the local per-user limiter
  (`AI_CHAT_RATE_LIMIT_PER_MINUTE=60`, `AI_CONFIRM_RATE_LIMIT_PER_MINUTE=30`) additionally sheds
  abusive duplicates while safe idempotent confirmation retries within budget always pass.
- Prometheus scrapes `ai-service:8090` (`docker/prometheus/prometheus.yml`). AI dashboards can use
  `seatflow_ai_chat_requests_total{result}`, `seatflow_ai_provider_requests_total{result}`,
  `seatflow_ai_provider_latency_seconds`, `seatflow_ai_tool_calls_total{tool,result}`,
  `seatflow_ai_proposals_total{result}`, `seatflow_ai_confirmations_total{result}`,
  `seatflow_ai_rate_limited_total{endpoint}`. All labels are low-cardinality by construction
  (never user/conversation/event/seat/prompt/key/response/exception text).

## 3. Troubleshooting

| Symptom (stable code) | Meaning | Safe diagnosis |
|---|---|---|
| `AI_DISABLED` | `AI_ENABLED=false` | Expected when AI is off; enable per section 1/2. |
| `AI_MISCONFIGURED` | enabled but key blank/invalid (`401/403`) | Check the secret is present in the runtime env (never in Git); restart `ai-service`. |
| `AI_RATE_LIMITED` | provider `429` **or** local per-user guard (`429`) | Wait and retry; do not hammer. Local guard resets each minute. Provider quota details are intentionally not exposed. |
| `AI_MODEL_UNAVAILABLE` | invalid/deprecated model (`400/404` naming the model) | Verify `GROQ_MODEL` (default `openai/gpt-oss-20b`) is still offered by Groq. |
| `AI_PROVIDER_TIMEOUT` | provider/connect/read timeout (bounded 15s, no retry storm) | Retry once; check Groq status; core booking is unaffected. |
| `AI_PROVIDER_UNAVAILABLE` | `5xx`/network outage/timeout | Assistant degraded only; verify normal event/reservation APIs directly. |
| `AI_RESPONSE_INVALID` | malformed provider envelope (broken JSON, invalid tool-call payload, empty content) with safe fallback | Retry once; assistant degraded only; core booking is unaffected. |
| Downstream tool outage (`RESERVATION_SERVICE_UNAVAILABLE`, `SESSION_NOT_BOOKABLE`) | Event/Reservation service error during revalidation | Check those services; the assistant never books from stale data. |
| Stale proposal (`PROPOSAL_EXPIRED` `410`, `PROPOSAL_SUPERSEDED`/`STALE_PROPOSAL`/price or seat change `409`) | proposal is not a hold; seats/price moved | Ask for fresh options and confirm the newest card; never reuse an old proposal ID. |
| `RESERVATION_RESULT_UNKNOWN_RETRY_SAFE` (`202`) | timeout after submit, result ambiguous | Retry the **same** confirmation: the server idempotency key prevents double booking. |

Logs carry correlation IDs plus safe event names (`AI_CHAT_STARTED`, `AI_TOOL_CALLED`,
`AI_PROPOSAL_CREATED`, `AI_PROPOSAL_CONFIRM_ATTEMPT`, `AI_RESERVATION_CREATED`,
`AI_PROVIDER_RATE_LIMITED`, `AI_PROVIDER_UNAVAILABLE`) — never chat text, keys, JWTs, payment
data, or chain-of-thought. Console and JSON appenders both apply secret masking.

## 4. Live Groq smoke test (opt-in, not CI)

`LiveGroqSmokeTest` (`@Tag("live")`, excluded from default CI via surefire `excludedGroups`):

```bash
AI_LIVE_SMOKE=true AI_ENABLED=true GROQ_API_KEY=<secret> \
  GROQ_BASE_URL=https://api.groq.com/openai/v1 GROQ_MODEL=openai/gpt-oss-20b \
  mvn -pl services/ai-service test -Dgroups=live -Dseatflow.excluded.groups= -Dtest=LiveGroqSmokeTest
```

`-Dseatflow.excluded.groups=` is required: the parent POM excludes the `live` tag by default
(property `seatflow.excluded.groups`, default `live`), and `-Dgroups=live` alone does not clear
that exclusion (exclude wins, selecting zero tests).

It performs one tiny provider ping (bounded output, no tools, no reservation). After it passes,
run the manual scenario: seeded data → normal USER → known event + small seat constraint →
expected read-only tools → structured proposal matches live data → explicit confirm → one
15-minute hold with authoritative expiry → existing checkout UI (no real-money processing).
