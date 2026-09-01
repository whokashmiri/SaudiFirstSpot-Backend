package com.billboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Internet Billboard MVP backend.
 *
 * <p>This service accepts paid "billboard listing" submissions, hands the
 * payment off to Moyasar, and once payment is confirmed (via webhook),
 * broadcasts the live, amount-ranked billboard to all connected clients
 * over STOMP/WebSocket.</p>
 */
@SpringBootApplication
public class BillboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillboardApplication.class, args);
    }
}
