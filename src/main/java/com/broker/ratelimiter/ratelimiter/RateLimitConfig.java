package com.broker.ratelimiter.ratelimiter;

public record RateLimitConfig(
        int bucketSize,
        int refillRatePerMinute
) {

    private static final double SECONDS_PER_MINUTE = 60.0;

    public RateLimitConfig {
        if (bucketSize <= 0) {
            throw new IllegalArgumentException("bucketSize must be greater than 0");
        }
        if (refillRatePerMinute <= 0) {
            throw new IllegalArgumentException("refillRatePerMinute must be greater than 0");
        }
    }

    public double refillRatePerSecond() {
        return refillRatePerMinute / SECONDS_PER_MINUTE;
    }
}
