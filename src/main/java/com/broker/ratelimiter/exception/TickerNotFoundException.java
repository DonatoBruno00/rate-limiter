package com.broker.ratelimiter.exception;

public class TickerNotFoundException extends RuntimeException {

    public TickerNotFoundException(String ticker) {
        super("Unknown ticker: " + ticker);
    }
}
