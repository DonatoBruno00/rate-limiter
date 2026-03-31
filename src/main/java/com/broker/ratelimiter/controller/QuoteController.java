package com.broker.ratelimiter.controller;

import com.broker.ratelimiter.model.Quote;
import com.broker.ratelimiter.service.QuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;

    @GetMapping("/{symbol}")
    public Quote getQuote(@PathVariable String symbol) {
        return quoteService.getQuote(symbol);
    }
}
