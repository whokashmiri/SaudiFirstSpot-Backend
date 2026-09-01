package com.billboard.service;

import com.billboard.dto.ListingRequestDTO;
import com.billboard.dto.PaymentResponseDTO;
import com.billboard.exception.GatewayException;
import com.billboard.exception.ListingNotFoundException;
import com.billboard.model.Listing;
import com.billboard.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full listing lifecycle: creating a {@code PENDING}
 * listing and Moyasar payment, reconciling Moyasar's webhook notification,
 * and broadcasting the live, amount-ranked billboard to connected clients.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillboardService {

    private static final String MOYASAR_INVOICES_URL = "https://api.moyasar.com/v1/invoices";
    private static final String BILLBOARD_TOPIC = "/topic/billboard";
    private static final String PAID_STATUS = "paid";

    private final ListingRepository listingRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;

    @Value("${moyasar.secret-key}")
    private String moyasarSecretKey;

    @Value("${moyasar.callback-url}")
    private String moyasarCallbackUrl;

    @Value("${moyasar.success-url}")
    private String moyasarSuccessUrl;

    /**
     * Creates a {@code PENDING} listing and initiates a matching payment
     * with Moyasar, returning the URL the frontend should redirect the user
     * to in order to complete checkout.
     *
     * <p>Note: Moyasar's Payment API expects card details to accompany a
     * {@code creditcard} source (typically supplied client-side via
     * Moyasar.js/Elements, which returns a one-time token or callback that
     * yields a {@code transaction_url} for 3-D Secure redirection). If your
     * integration instead needs a fully hosted checkout page generated
     * server-side, use Moyasar's Invoice API
     * ({@code POST /v1/invoices}, which returns a {@code url} field) rather
     * than the Payments API. This method follows the Payments API contract
     * as specified; adjust the request/response mapping below if you adopt
     * the Invoice API instead.</p>
     *
     * @param dto validated listing submission from the client
     * @return the Moyasar checkout/transaction URL to redirect the user to
     */
    public PaymentResponseDTO initiateListingPayment(ListingRequestDTO dto) {
        Listing listing = Listing.builder()
                .email(dto.getEmail())
                .amount(dto.getAmount())
                .details(dto.getDetails())
                .status(Listing.Status.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        listing = listingRepository.save(listing);

        long amountInHalalas = toHalalas(dto.getAmount());
        Map<String, Object> requestBody = buildMoyasarInvoiceRequest(amountInHalalas, dto.getDetails());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, buildMoyasarHeaders());

        Map<?, ?> responseBody = callMoyasar(request, listing);

        String invoiceId = requireString(responseBody, "id",
                "Moyasar response did not include an invoice id");
        String checkoutUrl = requireString(responseBody, "url",
                "Moyasar response did not include a checkout url");

        listing.setGatewayPaymentId(invoiceId);
        listingRepository.save(listing);

        return new PaymentResponseDTO(checkoutUrl);


    }

    /**
     * Reconciles a Moyasar webhook notification against the matching
     * listing, updating its status and broadcasting the refreshed billboard
     * whenever a payment succeeds.
     *
     * @param payload the raw webhook JSON body, expected to contain at
     *                least {@code id} and {@code status}
     */
    public void handleGatewayWebhook(Map<String, Object> payload) {
        Object idValue = payload.get("id");
        if (idValue == null) {
            throw new IllegalArgumentException("Webhook payload is missing 'id'");
        }
        String paymentId = idValue.toString();
        Object statusValue = payload.get("status");
        String status = statusValue == null ? null : statusValue.toString();

        Listing listing = listingRepository.findByGatewayPaymentId(paymentId)
                .orElseThrow(() -> new ListingNotFoundException(
                        "No listing found for gatewayPaymentId=" + paymentId));

        Listing.Status newStatus = PAID_STATUS.equalsIgnoreCase(status)
                ? Listing.Status.SUCCESS
                : Listing.Status.FAILED;
        listing.setStatus(newStatus);
        listingRepository.save(listing);

        log.info("Listing {} updated to {} via webhook (gatewayPaymentId={})",
                listing.getId(), newStatus, paymentId);

        if (newStatus == Listing.Status.SUCCESS) {
            broadcastUpdatedBillboard();
        }
    }

    /**
     * Publishes the current, amount-ranked list of successful listings to
     * every client subscribed to {@code /topic/billboard}.
     */
    public void broadcastUpdatedBillboard() {
        List<Listing> ranked = getActiveListings();
        messagingTemplate.convertAndSend(BILLBOARD_TOPIC, ranked);
        log.debug("Broadcast {} listing(s) to {}", ranked.size(), BILLBOARD_TOPIC);
    }

    /**
     * @return all {@code SUCCESS} listings ordered by amount, highest first
     */
    public List<Listing> getActiveListings() {
        return listingRepository.findByStatusOrderByAmountDesc(Listing.Status.SUCCESS);
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private long toHalalas(BigDecimal amountInSar) {
        return amountInSar
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private Map<String, Object> buildMoyasarInvoiceRequest(long amountInHalalas, String description) {
        Map<String, Object> source = new HashMap<>();
        source.put("type", "creditcard");

        Map<String, Object> body = new HashMap<>();
        body.put("amount", amountInHalalas);
        body.put("currency", "SAR");
        body.put("description", description);
        body.put("callback_url", moyasarCallbackUrl);
        body.put("source", source);
        return body;
    }

    private HttpHeaders buildMoyasarHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Moyasar uses HTTP Basic Auth with the secret key as the username
        // and an empty password.
        headers.setBasicAuth(moyasarSecretKey, "");
        return headers;
    }

    private Map<?, ?> callMoyasar(HttpEntity<Map<String, Object>> request, Listing listing) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(MOYASAR_INVOICES_URL, request, Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                throw new GatewayException("Moyasar returned an empty response body");
            }
            return body;
        } catch (RestClientException ex) {
            log.error("Moyasar gateway communication failure for listing {}", listing.getId(), ex);
            listing.setStatus(Listing.Status.FAILED);
            listingRepository.save(listing);
            throw new GatewayException("Failed to reach Moyasar payment gateway: " + ex.getMessage(), ex);
        }
    }



    private String requireString(Map<?, ?> map, String key, String errorMessage) {
        Object value = map.get(key);
        if (value == null) {
            throw new GatewayException(errorMessage);
        }
        return value.toString();
    }
}
