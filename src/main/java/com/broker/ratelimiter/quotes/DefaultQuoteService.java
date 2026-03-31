package com.broker.ratelimiter.quotes;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

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
        String upperSymbol = symbol.toUpperCase();
        return Optional.ofNullable(PRICES.get(upperSymbol))
                .map(price -> new Quote(upperSymbol, price, Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown symbol: " + symbol));
    }
}
