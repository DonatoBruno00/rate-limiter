package com.broker.ratelimiter.controller;

import com.broker.ratelimiter.exception.TickerNotFoundException;
import com.broker.ratelimiter.model.Quote;
import com.broker.ratelimiter.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuoteController.class)
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuoteService quoteService;

    @Test
    void shouldReturn200WithQuoteForKnownTicker() throws Exception {
        when(quoteService.getQuote("AAPL"))
                .thenReturn(Quote.builder().ticker("AAPL").price(new BigDecimal("189.50")).build());

        mockMvc.perform(get("/api/quotes/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.price").value(189.50));
    }

    @Test
    void shouldReturn404ForUnknownTicker() throws Exception {
        when(quoteService.getQuote("UNKNOWN"))
                .thenThrow(new TickerNotFoundException("UNKNOWN"));

        mockMvc.perform(get("/api/quotes/UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown ticker: UNKNOWN"));
    }

    @Test
    void shouldReturn500ForUnexpectedError() throws Exception {
        when(quoteService.getQuote("AAPL"))
                .thenThrow(new RuntimeException("Unexpected"));

        mockMvc.perform(get("/api/quotes/AAPL"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
