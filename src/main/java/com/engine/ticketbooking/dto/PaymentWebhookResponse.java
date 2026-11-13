package com.engine.ticketbooking.dto;

import com.engine.ticketbooking.entity.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentWebhookResponse {
    private UUID bookingId;
    private BookingStatus status;
    private String paymentReference;
    private String message;
    private Instant timestamp;
}
