package com.billboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single, independent billboard listing submission.
 *
 * <p>There is intentionally no separate user/account table: every submission
 * is self-contained and identified by the email address supplied at
 * checkout time. A listing starts life as {@link Status#PENDING} the moment
 * a Moyasar payment is initiated, and transitions to {@link Status#SUCCESS}
 * or {@link Status#FAILED} once the Moyasar webhook confirms the outcome.</p>
 */
@Document(collection = "listings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Listing {

    @Id
    private String id;

    private String email;

    private String product;

    private BigDecimal amount;

    private String details;

    /**
     * The payment id returned by Moyasar when the payment was created.
     * Indexed because every webhook lookup is keyed on this field.
     */
    @Indexed
    private String gatewayPaymentId;

    private Status status;

    private LocalDateTime createdAt;

    /**
     * Lifecycle of a listing's payment.
     */
    public enum Status {
        PENDING,
        SUCCESS,
        FAILED
    }
}
