package com.broker.ratelimiter.service;

import com.broker.ratelimiter.exception.TickerNotFoundException;
import com.broker.ratelimiter.model.Quote;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
    public Quote getQuote(String ticker) {
        String upperTicker = ticker.toUpperCase();
        return Optional.ofNullable(PRICES.get(upperTicker))
                .map(price -> Quote.builder().ticker(upperTicker).price(price).build())
                .orElseThrow(() -> new TickerNotFoundException(ticker));
    }
}
