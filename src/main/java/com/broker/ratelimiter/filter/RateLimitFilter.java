package com.broker.ratelimiter.filter;

import com.broker.ratelimiter.config.RateLimiterProperties;
import com.broker.ratelimiter.ratelimiter.RateLimitConfig;
import com.broker.ratelimiter.ratelimiter.RateLimitResult;
import com.broker.ratelimiter.ratelimiter.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimiterProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (isExcluded(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = extractClientKey(request);
        RateLimitConfig config = new RateLimitConfig(properties.getMaxTokens(), properties.getRefillRate());
        RateLimitResult result = rateLimiter.isAllowed(clientKey, config);

        if (result.allowed()) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(properties.getMaxTokens()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.getWriter().write("""
                    {"message": "Too many requests. Retry after %d seconds."}
                    """.formatted(result.retryAfterSeconds()));
        }
    }

    private boolean isExcluded(String path) {
        return properties.getExcludedPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String extractClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
