package com.broker.ratelimiter.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Requires Redis running on localhost:6379.
// Start it with: docker-compose up -d
// Tests are skipped automatically if Redis is not available.
class RedisRateLimiterTest {

    private static final int BUCKET_SIZE = 5;
    private static final int REFILL_RATE_PER_MINUTE = 5;
    private static final int THREAD_COUNT = 20;
    private static final int HIGH_BUCKET_SIZE = 100;

    private static final RateLimitConfig CONFIG = new RateLimitConfig(BUCKET_SIZE, REFILL_RATE_PER_MINUTE);

    private RedisRateLimiter redisRateLimiter;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        // Skip all tests if Redis is not available
        assumeTrue(isRedisAvailable(connectionFactory), "Redis not available on localhost:6379 — run docker-compose up -d");

        // Clean state before each test
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        redisRateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    void shouldAllowRequestWhenTokensAvailable() {
        RateLimitResult result = redisRateLimiter.isAllowed("client-1", CONFIG);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(BUCKET_SIZE - 1);
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    void shouldRejectRequestWhenTokensExhausted() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            redisRateLimiter.isAllowed("client-1", CONFIG);
        }

        RateLimitResult result = redisRateLimiter.isAllowed("client-1", CONFIG);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isZero();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void shouldRegenerateTokensAfterTime() throws InterruptedException {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            redisRateLimiter.isAllowed("client-1", CONFIG);
        }

        // With refillRate=5/min, 1 token regenerates every 12 seconds
        Thread.sleep(13_000);

        RateLimitResult result = redisRateLimiter.isAllowed("client-1", CONFIG);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldTrackKeysIndependently() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            redisRateLimiter.isAllowed("client-1", CONFIG);
        }

        RateLimitResult resultClient1 = redisRateLimiter.isAllowed("client-1", CONFIG);
        RateLimitResult resultClient2 = redisRateLimiter.isAllowed("client-2", CONFIG);

        assertThat(resultClient1.allowed()).isFalse();
        assertThat(resultClient2.allowed()).isTrue();
        assertThat(resultClient2.remainingTokens()).isEqualTo(BUCKET_SIZE - 1);
    }

    @Test
    void shouldBeAtomicUnderConcurrentRequests() throws Exception {
        RateLimitConfig highBucketConfig = new RateLimitConfig(HIGH_BUCKET_SIZE, 1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<RateLimitResult>> futures = new ArrayList<>();
        for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return redisRateLimiter.isAllowed("shared-key", highBucketConfig);
            }));
        }

        startLatch.countDown();

        AtomicInteger allowedCount = new AtomicInteger(0);
        for (Future<RateLimitResult> future : futures) {
            if (future.get().allowed()) {
                allowedCount.incrementAndGet();
            }
        }

        // Lua script ensures atomicity — exactly THREAD_COUNT requests allowed
        assertThat(allowedCount.get()).isEqualTo(THREAD_COUNT);
        executor.shutdown();
    }

    private boolean isRedisAvailable(LettuceConnectionFactory connectionFactory) {
        try {
            connectionFactory.getConnection().ping();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
