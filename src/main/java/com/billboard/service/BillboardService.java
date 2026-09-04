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
import org.springframework.http.HttpMethod;
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

    /** Server-to-server notification URL Moyasar POSTs the invoice to once paid. */
    @Value("${moyasar.callback-url}")
    private String moyasarCallbackUrl;

    /** Browser redirect URL the payer lands on after completing checkout. */
    @Value("${moyasar.success-url}")
    private String moyasarSuccessUrl;

    /**
     * Creates a {@code PENDING} listing and a matching Moyasar <b>Invoice</b>,
     * returning the hosted checkout URL the frontend should redirect the
     * user to.
     *
     * <p>This uses Moyasar's Invoice API ({@code POST /v1/invoices}) rather
     * than the Payments API: Invoices don't require card details up front
     * and return a ready-to-use {@code url} for a hosted payment page,
     * which fits a server-driven "redirect to pay" flow. Moyasar notifies
     * {@code callback_url} server-to-server when the invoice is paid, and
     * separately redirects the payer's browser to {@code success_url}.</p>
     *
     * @param dto validated listing submission from the client
     * @return the Moyasar checkout URL to redirect the user to
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

        Map<String, Object> requestBody = buildMoyasarInvoiceRequest(
                amountInHalalas, dto.getDetails(), listing.getId());
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

    /**
     * Looks up a single listing by its Mongo id for the frontend's
     * post-checkout "verifying payment" screen. If the listing is still
     * {@code PENDING}, actively re-checks the real status directly with
     * Moyasar first — this makes status checks self-healing even if the
     * webhook never arrived (wrong URL, dropped request, etc.), instead of
     * leaving the listing stuck forever waiting for a notification that's
     * never coming.
     */
    public Listing getListingById(String id) {
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ListingNotFoundException("No listing found for id=" + id));

        if (listing.getStatus() == Listing.Status.PENDING && listing.getGatewayPaymentId() != null) {
            listing = reconcileWithGateway(listing);
        }
        return listing;
    }

    /**
     * Fetches the invoice directly from Moyasar and updates our local
     * record to match, in case the webhook notification never arrived.
     * Broadcasts the refreshed billboard if this flips the listing to
     * {@code SUCCESS}.
     */
    private Listing reconcileWithGateway(Listing listing) {
        String url = MOYASAR_INVOICES_URL + "/" + listing.getGatewayPaymentId();
        HttpEntity<Void> request = new HttpEntity<>(buildMoyasarHeaders());

        Map<?, ?> body;
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            body = response.getBody();
        } catch (RestClientException ex) {
            log.warn("Could not reconcile listing {} with Moyasar: {}", listing.getId(), ex.getMessage());
            return listing;
        }

        if (body == null) {
            return listing;
        }

        Object statusValue = body.get("status");
        String status = statusValue == null ? null : statusValue.toString();
        Listing.Status resolvedStatus = PAID_STATUS.equalsIgnoreCase(status)
                ? Listing.Status.SUCCESS
                : null; // Moyasar invoice statuses like "initiated" mean still pending — leave as-is.

        if (resolvedStatus == null) {
            return listing;
        }

        listing.setStatus(resolvedStatus);
        listing = listingRepository.save(listing);

        log.info("Listing {} reconciled to {} directly from Moyasar (gatewayPaymentId={})",
                listing.getId(), resolvedStatus, listing.getGatewayPaymentId());

        broadcastUpdatedBillboard();
        return listing;
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

    private Map<String, Object> buildMoyasarInvoiceRequest(
            long amountInHalalas, String description, String listingId) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amountInHalalas);
        body.put("currency", "SAR");
        body.put("description", description);
        // Server-to-server "it's paid" notification, handled by handleGatewayWebhook().
        body.put("callback_url", moyasarCallbackUrl);
        // Browser redirect after the payer finishes checkout. We tag our own
        // listingId onto it so the frontend knows which listing to verify —
        // never trust Moyasar's own status/message query params here, since
        // anyone could hand-craft that URL. The webhook is the only source
        // of truth for whether payment actually succeeded.
        String separator = moyasarSuccessUrl.contains("?") ? "&" : "?";
        body.put("success_url", moyasarSuccessUrl + separator + "listingId=" + listingId);
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