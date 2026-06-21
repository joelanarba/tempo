package com.tempoapi.exception;

public class OfferExpiredException extends RuntimeException {

    public OfferExpiredException(String message) {
        super(message);
    }
}
