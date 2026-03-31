package com.broker.ratelimiter.quotes;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Service
public class DefaultQuoteService implements QuoteService {

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "AAPL",  new BigDecimal("189.50"),
            "GOOGL", new BigDecimal("141.20"),
            "MSFT",  new BigDecimal("378.90"),
            "AMZN",  new BigDecimal("182.10"),
            "TSLA",  new BigDecimal("175.30")
    );

    @Override
    public Quote getQuote(String symbol) {
        BigDecimal price = PRICES.get(symbol.toUpperCase());
        if (price == null) {
            throw new IllegalArgumentException("Unknown symbol: " + symbol);
        }
        return new Quote(symbol.toUpperCase(), price, Instant.now());
    }
}
