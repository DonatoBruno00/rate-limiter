# Spec 04 - Circuit Breaker + Fallback

## Objective

Wrap the Redis rate limiter with a Resilience4j circuit breaker that falls back to the in-memory Token Bucket when Redis is unavailable.

## Tasks

1. **ResilientRateLimiter** (implements `RateLimiter`): Primary: `RedisRateLimiter`. Fallback: `TokenBucket`. Uses Resilience4j `CircuitBreaker` to detect Redis failures. On open/half-open circuit, delegates to fallback. Logs fallback activation and recovery.

2. **Circuit breaker configuration**: Failure rate threshold, sliding window size, wait duration in open state, permitted calls in half-open state. Configurable via `application.yml` or code.

## Tests

- When Redis is up, requests go through `RedisRateLimiter`.
- When Redis goes down, circuit opens and requests fall back to `TokenBucket`.
- When Redis recovers, circuit transitions to half-open then closed; requests return to `RedisRateLimiter`.
- Rate limiting continues to function correctly during fallback.
