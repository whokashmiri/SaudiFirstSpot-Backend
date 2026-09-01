package com.billboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response returned to the frontend after a listing is created, containing
 * the URL the user must be redirected to in order to complete payment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponseDTO {
    private String paymentUrl;
}
