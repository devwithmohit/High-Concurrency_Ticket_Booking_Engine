package com.engine.ticketbooking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentWebhookRequest {

    @NotNull(message = "bookingId is required")
    private UUID bookingId;

    @NotBlank(message = "paymentReference is required")
    private String paymentReference;

    @NotNull(message = "amount is required")
    private BigDecimal amount;

    /**
     * Webhook payment status from payment provider: e.g. "SUCCESS", "PAID", "FAILED"
     */
    @NotBlank(message = "status is required")
    private String status;
}
