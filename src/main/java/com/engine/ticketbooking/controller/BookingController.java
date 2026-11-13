package com.engine.ticketbooking.controller;

import com.engine.ticketbooking.dto.LockSeatsRequest;
import com.engine.ticketbooking.dto.LockSeatsResponse;
import com.engine.ticketbooking.security.CustomUserPrincipal;
import com.engine.ticketbooking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * Two-Phase Seat Reservation:
     * POST /api/v1/bookings/lock
     *
     * Requires:
     * - Authorization: Bearer <JWT>
     * - Idempotency-Key: <unique-key>
     */
    @PostMapping("/lock")
    public ResponseEntity<LockSeatsResponse> lockSeats(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody LockSeatsRequest request) {

        LockSeatsResponse response = bookingService.lockSeats(principal.getId(), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
