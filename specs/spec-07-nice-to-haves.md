# Spec 07 - Nice to Haves

## Objective

Add observability, logging, and code quality improvements.

## Tasks

1. **Logging** (`@Slf4j`):
   - WARN on request rejection (include client key and path).
   - WARN on circuit breaker fallback activation.
   - INFO on circuit breaker recovery (transition back to closed).
   - DEBUG for allowed requests (optional, off by default).

2. **Micrometer Metrics**:
   - Counter `rate_limiter_allowed_total` - incremented on each allowed request.
   - Counter `rate_limiter_rejected_total` - incremented on each rejected request.
   - Counter `rate_limiter_fallback_total` - incremented on each fallback invocation.
   - Expose via `/actuator/prometheus` endpoint.

3. **Timeout and Pool Configuration**:
   - Verify Redis connection timeout and command timeout are set in `application.yml`.
   - Verify Lettuce pool settings (max-active, max-idle, min-idle) if applicable.

4. **Code Comments**:
   - Add meaningful Javadoc or inline comments explaining non-obvious logic (Lua script, circuit breaker transitions, token refill math).
