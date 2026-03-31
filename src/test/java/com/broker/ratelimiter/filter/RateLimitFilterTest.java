package com.broker.ratelimiter.filter;

import com.broker.ratelimiter.config.RateLimiterProperties;
import com.broker.ratelimiter.ratelimiter.RateLimitResult;
import com.broker.ratelimiter.ratelimiter.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimiter rateLimiter;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setMaxTokens(10);
        properties.setRefillRate(10);
        filter = new RateLimitFilter(rateLimiter, properties);
    }

    @Test
    void shouldAllowRequestAndSetHeaders() throws Exception {
        when(rateLimiter.isAllowed(anyString(), any())).thenReturn(RateLimitResult.allowed(9));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/quotes/AAPL");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
    }

    @Test
    void shouldRejectRequestWith429WhenLimitExceeded() throws Exception {
        when(rateLimiter.isAllowed(anyString(), any())).thenReturn(RateLimitResult.rejected(12));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/quotes/AAPL");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
    }

    @Test
    void shouldExtractClientKeyFromXForwardedForHeader() throws Exception {
        when(rateLimiter.isAllowed(anyString(), any())).thenReturn(RateLimitResult.allowed(9));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/quotes/AAPL");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
