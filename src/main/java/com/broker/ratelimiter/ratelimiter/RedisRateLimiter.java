package com.broker.ratelimiter.ratelimiter;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    // Loaded once at startup — Redis executes this atomically on every call
    private final DefaultRedisScript<List> rateLimitScript = buildScript();

    @Override
    public RateLimitResult isAllowed(String key, RateLimitConfig config) {
        long nowMillis = System.currentTimeMillis();

        String bucketSize       = String.valueOf(config.bucketSize());
        String refillRatePerSec = String.valueOf(config.refillRatePerSecond());
        String currentTimeMs    = String.valueOf(nowMillis);

        @SuppressWarnings("unchecked")
        List<Long> scriptResult = (List<Long>) redisTemplate.execute(
                rateLimitScript,
                List.of(key),
                bucketSize, refillRatePerSec, currentTimeMs
        );

        boolean allowed           = scriptResult.get(0) == 1L;
        int remainingTokens       = scriptResult.get(1).intValue();
        long retryAfterSeconds    = scriptResult.get(2);

        return allowed
                ? RateLimitResult.allowed(remainingTokens)
                : RateLimitResult.rejected(retryAfterSeconds);
    }

    private static DefaultRedisScript<List> buildScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}
