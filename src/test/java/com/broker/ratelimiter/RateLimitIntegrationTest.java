package com.broker.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate stringRedisTemplate;

    // application.yml: max-tokens=10, refill-rate=10 (1 token/6s)
    private static final String URL = "/api/quotes/AAPL";

    @BeforeEach
    void flushRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void requestsWithinLimitReturn200() {
        for (int i = 0; i < 10; i++) {
            assertThat(restTemplate.getForEntity(URL, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void eleventhRequestReturns429() {
        exhaustBucket();
        assertThat(restTemplate.getForEntity(URL, String.class).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void allowedResponseContainsRateLimitHeaders() {
        ResponseEntity<String> response = restTemplate.getForEntity(URL, String.class);

        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
    }

    @Test
    void rejectedResponseContainsRetryAfterHeader() {
        exhaustBucket();
        ResponseEntity<String> response = restTemplate.getForEntity(URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(response.getHeaders().getFirst("Retry-After"))).isPositive();
    }

    @Test
    void differentClientsHaveIndependentBuckets() {
        for (int i = 0; i < 11; i++) getWithIp("10.0.0.1");

        assertThat(getWithIp("10.0.0.1").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(getWithIp("10.0.0.2").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorHealthIsNotRateLimited() {
        exhaustBucket();
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokensRefillAfterWaiting() throws InterruptedException {
        exhaustBucket();
        // refill-rate=10 tokens/min → 1 token every 6 seconds
        Thread.sleep(7_000);
        assertThat(restTemplate.getForEntity(URL, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void exhaustBucket() {
        for (int i = 0; i < 10; i++) restTemplate.getForEntity(URL, String.class);
    }

    private ResponseEntity<String> getWithIp(String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        return restTemplate.exchange(URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
