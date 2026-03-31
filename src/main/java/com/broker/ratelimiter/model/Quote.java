package com.broker.ratelimiter.model;

import java.math.BigDecimal;

public record Quote(
        String symbol,
        BigDecimal price
) {}
