# SeatFlow Prometheus Acceptance Queries

Run these queries after exercising reservation, payment, ticket, and outbox flows. They are operational views only; PostgreSQL remains the business source of truth.

## HTTP 5xx rate

```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 1)
```

## HTTP P95 by application

```promql
histogram_quantile(0.95,
  sum by (le, application) (rate(http_server_requests_seconds_bucket[5m]))
)
```

## Reservation conflicts by reason

```promql
sum by (reason) (rate(seatflow_reservations_conflicts_total[5m]))
```

## Outbox publish P99 by service and event type

```promql
histogram_quantile(0.99,
  sum by (le, service, event_type) (rate(seatflow_outbox_publish_latency_seconds_bucket[5m]))
)
```
