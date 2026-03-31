package com.broker.ratelimiter.quotes;

import java.math.BigDecimal;

public record Quote(
        String symbol,
        BigDecimal price
) {}
