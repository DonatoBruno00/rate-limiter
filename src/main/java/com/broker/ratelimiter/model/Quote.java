package com.broker.ratelimiter.model;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record Quote(
        String symbol,
        BigDecimal price
) {}
