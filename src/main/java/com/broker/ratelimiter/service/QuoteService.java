package com.broker.ratelimiter.service;

import com.broker.ratelimiter.model.Quote;

public interface QuoteService {

    Quote getQuote(String ticker);
}
