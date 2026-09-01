package com.billboard.controller;

import com.billboard.dto.ListingRequestDTO;
import com.billboard.dto.PaymentResponseDTO;
import com.billboard.model.Listing;
import com.billboard.service.BillboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public HTTP surface for the Internet Billboard MVP.
 */
@RestController
@RequestMapping("/api/billboard")
@RequiredArgsConstructor
@Slf4j
public class BillboardController {

    private final BillboardService billboardService;

    /**
     * Accepts a new listing submission, creates a {@code PENDING} record,
     * and returns the Moyasar checkout URL for the client to redirect to.
     */
    @PostMapping("/submit")
    public ResponseEntity<PaymentResponseDTO> submit(@Valid @RequestBody ListingRequestDTO dto) {
        PaymentResponseDTO response = billboardService.initiateListingPayment(dto);
        return ResponseEntity.ok(response);
    }

    /**
     * Receives Moyasar's payment status webhook. Always responds 200 OK
     * once the payload has been processed so Moyasar does not retry
     * successfully-handled notifications.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> payload) {
        log.info("Received Moyasar webhook for payment id={} status={}",
                payload.get("id"), payload.get("status"));
        billboardService.handleGatewayWebhook(payload);
        return ResponseEntity.ok().build();
    }

    /**
     * Returns the current live billboard: successful listings ordered by
     * amount, highest bidder first.
     */
    @GetMapping
    public ResponseEntity<List<Listing>> getBillboard() {
        return ResponseEntity.ok(billboardService.getActiveListings());
    }
}
