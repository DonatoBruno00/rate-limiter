package com.broker.ratelimiter.quotes;

public interface QuoteService {

    Quote getQuote(String symbol);
}
