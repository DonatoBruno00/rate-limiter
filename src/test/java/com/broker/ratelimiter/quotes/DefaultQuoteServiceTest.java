package com.broker.ratelimiter.quotes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultQuoteServiceTest {

    private final DefaultQuoteService service = new DefaultQuoteService();

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "GOOGL", "MSFT", "AMZN", "TSLA"})
    void shouldReturnQuoteForKnownSymbol(String symbol) {
        Quote quote = service.getQuote(symbol);

        assertThat(quote.symbol()).isEqualTo(symbol);
        assertThat(quote.price()).isPositive();
    }

    @Test
    void shouldBeCaseInsensitive() {
        Quote lower = service.getQuote("aapl");
        Quote upper = service.getQuote("AAPL");

        assertThat(lower.symbol()).isEqualTo(upper.symbol());
        assertThat(lower.price()).isEqualByComparingTo(upper.price());
    }

    @Test
    void shouldThrowForUnknownSymbol() {
        assertThatThrownBy(() -> service.getQuote("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
