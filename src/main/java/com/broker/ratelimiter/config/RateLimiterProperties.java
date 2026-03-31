package com.broker.ratelimiter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    private int maxTokens = 10;
    private int refillRate = 10;
}
