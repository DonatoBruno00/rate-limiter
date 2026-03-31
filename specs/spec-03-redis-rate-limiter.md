# Spec 03 - Redis Rate Limiter

## Objective

Implement a Redis-backed Token Bucket rate limiter using a Lua script for atomic operations.

## Tasks

1. **token-bucket.lua** (`src/main/resources/scripts/`): Lua script that atomically reads/updates token count and last refill time in Redis. Accepts keys and args for maxTokens, refillRate, refillPeriodMillis, and current time. Returns allowed (0/1), remaining tokens, and retry-after millis.

2. **RedisRateLimiter** (implements `RateLimiter`): Uses `StringRedisTemplate` to execute the Lua script via `DefaultRedisScript`. Parses the script result into `RateLimitResult`.

3. **RedisConfig** (`@Configuration`): Bean for `StringRedisTemplate` and `DefaultRedisScript<List<Long>>` loading `token-bucket.lua`.

## Tests (Testcontainers)

- Request allowed and rejected behavior matches token bucket semantics.
- Atomic operation under concurrent access (multiple threads, single Redis).
- Tokens refill correctly after waiting.
- Keys are independent in Redis.
- Correct remaining tokens and retry-after values returned.
