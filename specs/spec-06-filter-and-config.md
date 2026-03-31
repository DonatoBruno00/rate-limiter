# Spec 06 - Filter + Configuration Wiring

## Objective

Wire the rate limiter into the request pipeline via a servlet filter and externalized configuration.

## Tasks

1. **RateLimiterProperties** (`@ConfigurationProperties(prefix = "rate-limiter")`): Fields: `maxTokens`, `refillRate`, `refillPeriodMillis`, `excludedPaths` (list, e.g., `/actuator/**`). Binds to `application.yml`.

2. **RateLimitFilter** (extends `OncePerRequestFilter`): Extracts client key from `X-Forwarded-For` header or `request.getRemoteAddr()`. Skips excluded paths. Calls `RateLimiter.tryConsume()`. On allowed: sets `X-RateLimit-Remaining` and `X-RateLimit-Limit` headers; proceeds. On rejected: returns 429 with `Retry-After` header and JSON error body.

3. **RateLimiterConfig** (`@Configuration`): Defines beans for `TokenBucket`, `RedisRateLimiter`, `ResilientRateLimiter`, `RateLimitFilter`. Builds `RateLimitConfig` from `RateLimiterProperties`.

4. **application.yml** (full): Server port, Redis connection, rate-limiter properties, circuit breaker settings, actuator endpoints, logging levels.

## Tests

- Requests within limit pass through with correct headers.
- Requests exceeding limit receive 429 with `Retry-After`.
- Excluded paths (e.g., health) bypass rate limiting.
- Client key extraction from `X-Forwarded-For` works correctly.
