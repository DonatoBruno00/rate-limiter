package com.broker.ratelimiter.ratelimiter;

public interface RateLimiter {

    RateLimitResult isAllowed(String key, RateLimitConfig config);
}
