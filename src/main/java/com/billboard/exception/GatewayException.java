package com.billboard.exception;

/**
 * Raised when communication with the Moyasar payment gateway fails, or when
 * the gateway returns a response we cannot make sense of (e.g. missing
 * {@code id} or {@code transaction_url}).
 */
public class GatewayException extends RuntimeException {
    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
