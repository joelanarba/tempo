package com.tempoapi.exception;

public class AlreadyWaitlistedException extends RuntimeException {

    public AlreadyWaitlistedException(String message) {
        super(message);
    }
}
