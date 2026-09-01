package com.billboard.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Payload submitted by the client to create a new billboard listing and
 * kick off a Moyasar payment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListingRequestDTO {

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "1.00", message = "amount must be at least 1.00 SAR")
    private BigDecimal amount;

    @NotBlank(message = "details is required")
    private String details;
}
