package com.broker.ratelimiter.service;

import com.broker.ratelimiter.exception.TickerNotFoundException;
import com.broker.ratelimiter.model.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultQuoteServiceTest {

    private final DefaultQuoteService service = new DefaultQuoteService();

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "GOOGL", "MSFT", "AMZN", "TSLA"})
    void shouldReturnQuoteForKnownTicker(String ticker) {
        Quote quote = service.getQuote(ticker);

        assertThat(quote.ticker()).isEqualTo(ticker);
        assertThat(quote.price()).isPositive();
    }

    @Test
    void shouldBeCaseInsensitive() {
        Quote lower = service.getQuote("aapl");
        Quote upper = service.getQuote("AAPL");

        assertThat(lower.ticker()).isEqualTo(upper.ticker());
        assertThat(lower.price()).isEqualByComparingTo(upper.price());
    }

    @Test
    void shouldThrowForUnknownTicker() {
        assertThatThrownBy(() -> service.getQuote("UNKNOWN"))
                .isInstanceOf(TickerNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
