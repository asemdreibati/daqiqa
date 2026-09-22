# Promise endpoint: baseline (2026-09-22)

A first measurement of `POST /v1/promises`, the hot path, right after wave 1 was integrated. It is a smoke-level
baseline on a deliberately small machine, not the wave-3 load test (k6, dedicated hardware, published dashboards).

## Setup

| | |
|---|---|
| Machine | 2 vCPU, 8 GB RAM Linux sandbox. The load generator, the server and PostgreSQL all share the same two cores |
| Server | `daqiqa-server.jar` (Spring Boot 4.1.1, Java 21, virtual threads), `-Xmx768m`, Hikari pool 20, event publisher `logging` |
| Database | PostgreSQL 16 on the same host, Dubai demo data (12 stores, 52 products, 624 stock rows) |
| Request | Basket of 2 lines (3 units) near Dubai Marina; two stores cover the point. Every request: coverage lookup, one SQL round trip for stock, ETA calculation, HMAC-SHA256 signature |
| Tool | Apache Bench 2.3 with keep-alive: `ab -k -n 10000 -c <C> -p promise-body.json -T application/json http://127.0.0.1:8080/v1/promises` |
| Latency source | Server side, from the `daqiqa.promise.requests` percentile histogram: the difference between Prometheus scrapes taken before and after each run. Values are histogram bucket upper bounds |

The JVM was warmed up with 24,000 requests before the measured runs.

## Results

| Concurrent clients | Throughput | p50 | p90 | p99 | Failed |
|---|---|---|---|---|---|
| 4 | 1,826 req/s | ≤ 1.0 ms | ≤ 2.5 ms | ≤ 7.0 ms | 0 |
| 8 | 2,370 req/s | ≤ 1.0 ms | ≤ 4.2 ms | ≤ 14.0 ms | 0 |
| 32 | 3,136 req/s | ≤ 3.8 ms | ≤ 14.0 ms | ≤ 55.9 ms | 0 |

## Reading

- **The target is met with room to spare.** The quality target is p99 < 25 ms at 1,000 requests per second per
  instance ([quality attributes](../architecture/quality-attributes.md)). At 2,370 requests per second, p99 is at most
  14 ms.
- **The work itself is about a millisecond** (p50 ≤ 1 ms at low concurrency). The tail at 32 clients is CPU queueing:
  two cores are shared by the load generator, the server and PostgreSQL, so latency there measures the sandbox more
  than the code.
- **No errors under load**, and none of the 44,000 requests needed more than one database round trip.

## Not measured yet

Checkout latency, behaviour with Kafka publishing, multiple replicas, and results on dedicated hardware. These belong to
the wave-3 load tests and chaos drills in the [roadmap](../roadmap.md).
