package com.engine.ticketbooking.controller;

import com.engine.ticketbooking.dto.PaymentWebhookRequest;
import com.engine.ticketbooking.dto.PaymentWebhookResponse;
import com.engine.ticketbooking.service.PaymentWebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    /**
     * Webhook endpoint to confirm payment and finalize seat reservations.
     * Enforces Idempotency-Key check and handles 10:01 boundary race conditions.
     */
    @PostMapping("/payment")
    public ResponseEntity<PaymentWebhookResponse> handlePaymentWebhook(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody PaymentWebhookRequest request) {

        PaymentWebhookResponse response = paymentWebhookService.processPaymentWebhook(idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
