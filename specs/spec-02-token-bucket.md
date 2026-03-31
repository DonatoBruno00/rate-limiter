# Spec 02 - Token Bucket Algorithm

## Objective

Implement the Token Bucket rate-limiting algorithm as an in-memory solution using `ConcurrentHashMap`.

## Tasks

1. **RateLimitResult** (record): Fields `allowed` (boolean), `remainingTokens` (long), `retryAfterMillis` (long).

2. **RateLimitConfig** (value object): Fields `maxTokens` (int), `refillRate` (int), `refillPeriodMillis` (long). Validation on construction.

3. **RateLimiter** (interface): Method `RateLimitResult tryConsume(String key, RateLimitConfig config)`.

4. **TokenBucket** (class implementing `RateLimiter`): In-memory implementation backed by `ConcurrentHashMap`. Each key tracks current tokens and last refill timestamp. Thread-safe token consumption and refill logic.

## Tests

- Request allowed when tokens available.
- Request rejected when tokens exhausted.
- Tokens refill over time.
- Tokens do not exceed max capacity.
- Burst of requests up to max tokens succeeds.
- `retryAfterMillis` is positive when rejected, zero when allowed.
- Independent keys do not interfere with each other.
- Thread safety under concurrent access.
