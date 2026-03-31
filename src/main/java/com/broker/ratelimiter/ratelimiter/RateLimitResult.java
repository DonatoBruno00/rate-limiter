package com.broker.ratelimiter.ratelimiter;

public record RateLimitResult(
        boolean allowed,
        int remainingTokens,
        long retryAfterSeconds
) {

    public static RateLimitResult allowed(int remainingTokens) {
        return new RateLimitResult(true, remainingTokens, 0);
    }

    public static RateLimitResult rejected(long retryAfterSeconds) {
        return new RateLimitResult(false, 0, retryAfterSeconds);
    }
}
