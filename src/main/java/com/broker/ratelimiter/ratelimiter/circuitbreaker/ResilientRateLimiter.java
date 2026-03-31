package com.broker.ratelimiter.ratelimiter.circuitbreaker;

import com.broker.ratelimiter.ratelimiter.RateLimitConfig;
import com.broker.ratelimiter.ratelimiter.RateLimitResult;
import com.broker.ratelimiter.ratelimiter.RateLimiter;
import com.broker.ratelimiter.ratelimiter.TokenBucket;
import com.broker.ratelimiter.ratelimiter.redis.RedisRateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

    // In-memory fallback — one shared instance, no Redis dependency
    private final TokenBucket fallback = new TokenBucket();

    public ResilientRateLimiter(RedisRateLimiter redisRateLimiter,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.redisRateLimiter = redisRateLimiter;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    @Override
    public RateLimitResult isAllowed(String key, RateLimitConfig config) {
        try {
            return circuitBreaker.executeSupplier(() -> redisRateLimiter.isAllowed(key, config));
        } catch (Exception cause) {
            log.warn("Redis rate limiter unavailable ({}: {}), falling back to in-memory bucket for key={}",
                    cause.getClass().getSimpleName(), cause.getMessage(), key);
            return fallback.isAllowed(key, config);
        }
    }
}
