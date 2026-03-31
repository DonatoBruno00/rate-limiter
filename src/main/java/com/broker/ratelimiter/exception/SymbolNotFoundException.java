package com.broker.ratelimiter.exception;

public class SymbolNotFoundException extends RuntimeException {

    public SymbolNotFoundException(String symbol) {
        super("Unknown symbol: " + symbol);
    }
}
