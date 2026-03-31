package com.broker.ratelimiter.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    // Fake clock injected into TokenBucket — advance it without any Thread.sleep()
    private AtomicLong fakeNanos;
    private TokenBucket tokenBucket;

    private static final int BUCKET_SIZE = 5;
    private static final int REFILL_RATE_PER_MINUTE = 5;
    private static final int EXTRA_REQUESTS_AFTER_EXHAUSTION = 3;
    private static final int THREAD_COUNT = 20;
    private static final int HIGH_BUCKET_SIZE = 100;

    // With 5 tokens/min: 1 token every 12 seconds — advance 13s to guarantee refill
    private static final long NANOS_TO_REGENERATE_ONE_TOKEN = 13L * 1_000_000_000L;

    private static final RateLimitConfig CONFIG = new RateLimitConfig(BUCKET_SIZE, REFILL_RATE_PER_MINUTE);

    @BeforeEach
    void setUp() {
        fakeNanos = new AtomicLong(0);
        tokenBucket = new TokenBucket(fakeNanos::get);
    }

    @Test
    void shouldAllowRequestWhenTokensAvailable() {
        RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(BUCKET_SIZE - 1);
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    void shouldRejectRequestWhenTokensExhausted() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            tokenBucket.isAllowed("client-1", CONFIG);
        }

        RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isZero();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void shouldRegenerateTokensAfterTime() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            tokenBucket.isAllowed("client-1", CONFIG);
        }

        // Advance the fake clock — no actual waiting needed
        fakeNanos.addAndGet(NANOS_TO_REGENERATE_ONE_TOKEN);

        RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldNotExceedBucketSizeWhenRegenerating() {
        // Consume 1 token, then advance enough time to regenerate more than 1 token
        tokenBucket.isAllowed("client-1", CONFIG);
        fakeNanos.addAndGet(NANOS_TO_REGENERATE_ONE_TOKEN * 2);

        RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);

        // Cap ensures we can't go above BUCKET_SIZE - 1 remaining (we already consumed one more)
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isLessThanOrEqualTo(BUCKET_SIZE - 1);
    }

    @Test
    void shouldRejectBurstAfterExhaustingTokens() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);
            assertThat(result.allowed()).isTrue();
        }

        for (int requestIndex = 0; requestIndex < EXTRA_REQUESTS_AFTER_EXHAUSTION; requestIndex++) {
            RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);
            assertThat(result.allowed()).isFalse();
        }
    }

    @Test
    void shouldCalculateRetryAfterCorrectly() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            tokenBucket.isAllowed("client-1", CONFIG);
        }

        RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);

        // 5 tokens/min = 1 token every 12 seconds → retry must be exactly 12s
        assertThat(result.retryAfterSeconds()).isEqualTo(12L);
    }

    @Test
    void shouldTrackKeysIndependently() {
        for (int requestIndex = 0; requestIndex < BUCKET_SIZE; requestIndex++) {
            tokenBucket.isAllowed("client-1", CONFIG);
        }

        RateLimitResult resultClient1 = tokenBucket.isAllowed("client-1", CONFIG);
        RateLimitResult resultClient2 = tokenBucket.isAllowed("client-2", CONFIG);

        assertThat(resultClient1.allowed()).isFalse();
        assertThat(resultClient2.allowed()).isTrue();
        assertThat(resultClient2.remainingTokens()).isEqualTo(BUCKET_SIZE - 1);
    }

    @Test
    void shouldBeThreadSafe() throws Exception {
        RateLimitConfig highBucketConfig = new RateLimitConfig(HIGH_BUCKET_SIZE, 1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<RateLimitResult>> futures = new ArrayList<>();
        for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return tokenBucket.isAllowed("shared-key", highBucketConfig);
            }));
        }

        startLatch.countDown();

        AtomicInteger allowedCount = new AtomicInteger(0);
        for (Future<RateLimitResult> future : futures) {
            if (future.get().allowed()) {
                allowedCount.incrementAndGet();
            }
        }

        assertThat(allowedCount.get()).isEqualTo(THREAD_COUNT);
        executor.shutdown();
    }

    @Test
    void shouldDecreaseRemainingTokensWithEachRequest() {
        for (int expectedRemaining = BUCKET_SIZE - 1; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitResult result = tokenBucket.isAllowed("client-1", CONFIG);
            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(expectedRemaining);
        }
    }
}
