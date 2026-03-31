package com.broker.ratelimiter.ratelimiter.redis;

import com.broker.ratelimiter.ratelimiter.RateLimitConfig;
import com.broker.ratelimiter.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Starts a real Redis 7 container via Docker — no local Redis required.
@Testcontainers
class RedisRateLimiterTest {

    private static final int REDIS_PORT = 6379;

    // Prefix isolates test keys from any other data in the container
    private static final String TEST_KEY_PREFIX = "test:rl:";

    private static final int BUCKET_SIZE = 5;
    private static final int REFILL_RATE_PER_MINUTE = 5;
    private static final int THREAD_COUNT = 20;
    private static final int HIGH_BUCKET_SIZE = 100;

    private static final RateLimitConfig CONFIG = new RateLimitConfig(BUCKET_SIZE, REFILL_RATE_PER_MINUTE);

    @Container
    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private RedisRateLimiter redisRateLimiter;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        redisRateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @AfterEach
    void cleanUp() {
        // Delete only test-prefixed keys after each test
        Set<String> testKeys = redisTemplate.keys(TEST_KEY_PREFIX + "*");
        if (testKeys != null && !testKeys.isEmpty()) {
            redisTemplate.delete(testKeys);
        }
    }

    @Test
    void shouldAllowRequestWhenTokensAvailable() {
        RateLimitResult result = redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(BUCKET_SIZE - 1);
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    void shouldRejectRequestWhenTokensExhausted() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);
        }

        RateLimitResult result = redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isZero();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void shouldRegenerateTokensAfterTime() throws InterruptedException {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);
        }

        // With refillRate=5/min, 1 token regenerates every 12 seconds
        Thread.sleep(13_000);

        RateLimitResult result = redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldTrackKeysIndependently() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);
        }

        RateLimitResult resultClient1 = redisRateLimiter.isAllowed(testKey("client-1"), CONFIG);
        RateLimitResult resultClient2 = redisRateLimiter.isAllowed(testKey("client-2"), CONFIG);

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
                return redisRateLimiter.isAllowed(testKey("shared-key"), highBucketConfig);
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

    private String testKey(String clientId) {
        return TEST_KEY_PREFIX + clientId;
    }
}
