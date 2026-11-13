package com.engine.ticketbooking.dto;

import com.engine.ticketbooking.entity.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockSeatsResponse {
    private UUID bookingId;
    private UUID userId;
    private UUID eventId;
    private List<UUID> seatIds;
    private BigDecimal totalAmount;
    private BookingStatus status;
    private String idempotencyKey;
    private Instant expiresAt;
    private Instant createdAt;
    private String message;
}
