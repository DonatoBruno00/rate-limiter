# Spec 08 - Integration Tests E2E

## Objective

End-to-end integration tests using `@SpringBootTest` with Testcontainers to verify the full rate-limiting pipeline.

## Tasks

Write integration tests covering:

1. **Rate limiting enforced**: Send 5 requests (within limit) and verify 200; send 6th request and verify 429.
2. **Response headers correct**: `X-RateLimit-Limit` and `X-RateLimit-Remaining` present and accurate on allowed responses.
3. **Retry-After header**: Present on 429 responses with a positive value.
4. **Independent IPs**: Two different client IPs each get their own token bucket (both can send up to the limit independently).
5. **Health endpoint excluded**: `GET /actuator/health` is never rate-limited regardless of token state.
6. **Token refill**: After exhausting tokens, wait for refill period, then verify requests are allowed again.
7. **Metrics update**: After allowed and rejected requests, `/actuator/prometheus` reflects correct counter values for `rate_limiter_allowed_total` and `rate_limiter_rejected_total`.

## Setup

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate`.
- Testcontainers `GenericContainer` for Redis.
- `@DynamicPropertySource` to override `spring.data.redis.host` and `port`.
