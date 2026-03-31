package com.broker.ratelimiter.ratelimiter;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class ResilientRateLimiterTest {

    // Large enough that no single test exhausts the fallback bucket
    private static final int HIGH_BUCKET_SIZE = 100;
    private static final int REFILL_RATE_PER_MINUTE = 60;
    private static final RateLimitConfig CONFIG = new RateLimitConfig(HIGH_BUCKET_SIZE, REFILL_RATE_PER_MINUTE);

    // Number of consecutive failures needed to open the circuit (matches application.yml)
    private static final int FAILURES_TO_OPEN = 5;

    @Autowired
    private ResilientRateLimiter resilientRateLimiter;

    @MockBean
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("redis-rate-limiter").reset();
    }

    @Test
    void shouldUseRedisWhenHealthy() {
        when(redisRateLimiter.isAllowed(anyString(), any()))
                .thenReturn(RateLimitResult.allowed(HIGH_BUCKET_SIZE - 1));

        RateLimitResult result = resilientRateLimiter.isAllowed("healthy-client", CONFIG);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(HIGH_BUCKET_SIZE - 1);
    }

    @Test
    void shouldFallBackToInMemoryWhenRedisThrows() {
        when(redisRateLimiter.isAllowed(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis is down"));

        RateLimitResult result = resilientRateLimiter.isAllowed("fallback-client", CONFIG);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldOpenCircuitAfterConsecutiveFailures() {
        when(redisRateLimiter.isAllowed(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis is down"));

        for (int i = 0; i < FAILURES_TO_OPEN; i++) {
            resilientRateLimiter.isAllowed("open-circuit-client", CONFIG);
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("redis-rate-limiter");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void shouldContinueServingRequestsViaFallbackWhenCircuitIsOpen() {
        when(redisRateLimiter.isAllowed(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis is down"));

        // Open the circuit
        for (int i = 0; i < FAILURES_TO_OPEN; i++) {
            resilientRateLimiter.isAllowed("open-serve-client", CONFIG);
        }

        // Even with circuit OPEN, fallback keeps serving requests
        RateLimitResult result = resilientRateLimiter.isAllowed("open-serve-client", CONFIG);
        assertThat(result.allowed()).isTrue();
    }
}
