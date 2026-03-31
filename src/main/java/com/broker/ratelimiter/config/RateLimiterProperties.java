package com.broker.ratelimiter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    private int maxTokens = 10;
    private int refillRate = 10;
    private List<String> excludedPaths = List.of("/actuator/**", "/actuator/health");
}
