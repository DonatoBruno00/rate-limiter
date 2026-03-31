package com.broker.ratelimiter.controller;

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
    void shouldReturn200WithQuoteForKnownSymbol() throws Exception {
        when(quoteService.getQuote("AAPL"))
                .thenReturn(new Quote("AAPL", new BigDecimal("189.50")));

        mockMvc.perform(get("/api/quotes/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.price").value(189.50));
    }

    @Test
    void shouldReturn400ForUnknownSymbol() throws Exception {
        when(quoteService.getQuote("UNKNOWN"))
                .thenThrow(new IllegalArgumentException("Unknown symbol: UNKNOWN"));

        mockMvc.perform(get("/api/quotes/UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown symbol: UNKNOWN"));
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
