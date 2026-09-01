package com.billboard.exception;

/**
 * Raised when a webhook refers to a {@code gatewayPaymentId} that does not
 * match any listing in the database.
 */
public class ListingNotFoundException extends RuntimeException {
    public ListingNotFoundException(String message) {
        super(message);
    }
}
