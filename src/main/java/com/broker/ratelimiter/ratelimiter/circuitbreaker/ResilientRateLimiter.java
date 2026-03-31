package com.broker.ratelimiter.ratelimiter.circuitbreaker;

import com.broker.ratelimiter.ratelimiter.RateLimitConfig;
import com.broker.ratelimiter.ratelimiter.RateLimitResult;
import com.broker.ratelimiter.ratelimiter.RateLimiter;
import com.broker.ratelimiter.ratelimiter.TokenBucket;
import com.broker.ratelimiter.ratelimiter.redis.RedisRateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
public class ResilientRateLimiter implements RateLimiter {

    private static final String CIRCUIT_BREAKER_NAME = "redis-rate-limiter";

    private final RedisRateLimiter redisRateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;

    // In-memory fallback — one shared instance, no Redis dependency
    private final TokenBucket fallback = new TokenBucket();

    public ResilientRateLimiter(RedisRateLimiter redisRateLimiter,
                                CircuitBreakerRegistry circuitBreakerRegistry,
                                MeterRegistry meterRegistry) {
        this.redisRateLimiter = redisRateLimiter;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public RateLimitResult isAllowed(String key, RateLimitConfig config) {
        try {
            return circuitBreaker.executeSupplier(() -> redisRateLimiter.isAllowed(key, config));
        } catch (Exception cause) {
            meterRegistry.counter("rate_limiter_fallback_total").increment();
            log.warn("Redis unavailable, falling back to in-memory for key={}", key);
            return fallback.isAllowed(key, config);
        }
    }
}
