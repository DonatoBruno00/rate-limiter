# Spec 09 - DESIGN.md

## Objective

Write a `DESIGN.md` document at the project root that explains the architecture, decisions, and trade-offs of the rate limiter system.

## Sections to Include

1. **Overview**: High-level summary of the project -- a per-client IP token bucket rate limiter for a REST API, backed by Redis with in-memory fallback.

2. **Algorithm Comparison**: Compare token bucket vs. sliding window vs. fixed window vs. leaky bucket. Justify choosing token bucket (burst-friendly, simple, O(1)).

3. **Architecture Diagram**: ASCII or textual diagram showing: Client -> Filter -> ResilientRateLimiter -> Redis / TokenBucket fallback -> QuoteController.

4. **Redis + Lua Storage**: Explain why Lua scripts ensure atomicity in Redis. Describe the keys and values stored. Discuss TTL strategy.

5. **Circuit Breaker Resilience**: Explain Resilience4j circuit breaker states (closed, open, half-open). Describe fallback behavior and recovery.

6. **Nice-to-Haves**: Summarize logging, metrics, and observability additions.

7. **Unimplemented Endpoint**: Describe `POST /api/orders` as a planned but unimplemented endpoint. Explain how it would be rate-limited differently (stricter limits for write operations).

8. **Testing Strategy**: Unit tests (TokenBucket), integration tests (Redis + Testcontainers), E2E tests (full pipeline). Explain test isolation approach.

9. **Future Improvements**: Sliding window hybrid, distributed rate limiting across multiple instances, rate limit by API key, dynamic config reload, Redis Cluster support.

10. **AI Usage**: Disclose how AI tools were used during development (code generation, design review, documentation).
