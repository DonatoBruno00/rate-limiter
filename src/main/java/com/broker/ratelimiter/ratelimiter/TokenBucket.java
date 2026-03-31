package com.broker.ratelimiter.ratelimiter;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TokenBucket implements RateLimiter {

    // Internal state per client: how many tokens remain and when we last refilled
    private record BucketState(double tokens, long lastRefillNanos) {}

    // Pairs the new bucket state with the rate limit decision so compute() can return both
    private record Evaluation(BucketState state, RateLimitResult result) {}

    private static final double NANOSECONDS_PER_SECOND = 1_000_000_000.0;

    // One bucket per client key (e.g. "rl:192.168.1.1:/api/quotes")
    private final ConcurrentHashMap<String, Evaluation> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult isAllowed(String key, RateLimitConfig config) {
        // compute() locks this key atomically — no two threads can modify the same key at once
        Evaluation evaluation = buckets.compute(key, (bucketKey, currentEvaluation) -> {
            long nowNanos = System.nanoTime();

            // First request for this key: start with a full bucket
            BucketState currentBucketState = Optional.ofNullable(currentEvaluation)
                    .map(Evaluation::state)
                    .orElse(new BucketState(config.bucketSize(), nowNanos));

            BucketState refilledBucketState = refill(currentBucketState, config, nowNanos);

            return evaluate(refilledBucketState, config, nowNanos);
        });

        return evaluation.result();
    }

    private BucketState refill(BucketState currentBucketState, RateLimitConfig config, long nowNanos) {
        double elapsedSeconds = (nowNanos - currentBucketState.lastRefillNanos()) / NANOSECONDS_PER_SECOND;
        double tokensToAdd = elapsedSeconds * config.refillRatePerSecond();

        // Not enough time passed to regenerate any token — skip object creation
        if (tokensToAdd <= 0) {
            return currentBucketState;
        }

        // Cap at bucketSize so idle clients don't accumulate unlimited tokens
        double refilledTokens = Math.min(currentBucketState.tokens() + tokensToAdd, config.bucketSize());
        return new BucketState(refilledTokens, nowNanos);
    }

    private Evaluation evaluate(BucketState refilledBucketState, RateLimitConfig config, long nowNanos) {
        if (refilledBucketState.tokens() >= 1.0) {
            double remainingAfterConsume = refilledBucketState.tokens() - 1.0;
            return new Evaluation(
                    new BucketState(remainingAfterConsume, nowNanos),
                    RateLimitResult.allowed((int) remainingAfterConsume)
            );
        }

        // ceil so the client waits long enough to actually have a token when it retries
        double tokensNeeded = 1.0 - refilledBucketState.tokens();
        long retryAfterSeconds = (long) Math.ceil(tokensNeeded / config.refillRatePerSecond());
        return new Evaluation(
                new BucketState(refilledBucketState.tokens(), nowNanos),
                RateLimitResult.rejected(retryAfterSeconds)
        );
    }
}
