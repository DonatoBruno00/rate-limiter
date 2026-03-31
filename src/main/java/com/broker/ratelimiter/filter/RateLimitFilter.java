package com.broker.ratelimiter.filter;

import com.broker.ratelimiter.config.RateLimiterProperties;
import com.broker.ratelimiter.ratelimiter.RateLimitConfig;
import com.broker.ratelimiter.ratelimiter.RateLimitResult;
import com.broker.ratelimiter.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimiterProperties properties;
    private final MeterRegistry meterRegistry;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return properties.getExcludedPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientKey = extractClientKey(request);
        RateLimitConfig config = new RateLimitConfig(properties.getMaxTokens(), properties.getRefillRate());
        RateLimitResult result = rateLimiter.isAllowed(clientKey, config);

        if (result.allowed()) {
            meterRegistry.counter("rate_limiter_allowed_total").increment();
            response.setHeader("X-RateLimit-Limit", String.valueOf(properties.getMaxTokens()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            meterRegistry.counter("rate_limiter_rejected_total").increment();
            log.warn("Rate limit exceeded for key={} path={}", clientKey, request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests\"}");
        }
    }

    // Prefer X-Forwarded-For when present (requests come through a load balancer).
    // Fall back to the direct socket address for local/direct connections.
    private String extractClientKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
