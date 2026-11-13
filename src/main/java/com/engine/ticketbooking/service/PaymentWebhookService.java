package com.engine.ticketbooking.service;

import com.engine.ticketbooking.dto.PaymentWebhookRequest;
import com.engine.ticketbooking.dto.PaymentWebhookResponse;
import com.engine.ticketbooking.entity.Booking;
import com.engine.ticketbooking.entity.BookingSeat;
import com.engine.ticketbooking.entity.BookingStatus;
import com.engine.ticketbooking.entity.Seat;
import com.engine.ticketbooking.entity.SeatStatus;
import com.engine.ticketbooking.repository.BookingRepository;
import com.engine.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final RedisLockService redisLockService;
    private final SeatMapCacheService seatMapCacheService;

    /**
     * Confirms bookings upon receiving payment webhooks.
     * Enforces strict validation of seat lock validity and owner identity.
     * Handles the 10:01 boundary race condition by marking late payments as REFUND_INITIATED
     * rather than overwriting seats that may have already been released to or claimed by other users.
     */
    @Transactional
    public PaymentWebhookResponse processPaymentWebhook(String idempotencyKey, PaymentWebhookRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is mandatory for payment webhooks");
        }

        UUID bookingId = request.getBookingId();

        // Fetch booking with Pessimistic Write Lock
        Booking booking = bookingRepository.findByIdWithPessimisticLock(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with ID: " + bookingId));

        // 1. Idempotent check: if already terminal, return existing status without re-processing
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Payment webhook idempotent replay: booking {} is already CONFIRMED", bookingId);
            return PaymentWebhookResponse.builder()
                    .bookingId(bookingId)
                    .status(BookingStatus.CONFIRMED)
                    .paymentReference(request.getPaymentReference())
                    .message("Booking is already confirmed (idempotent replay)")
                    .timestamp(Instant.now())
                    .build();
        }

        if (booking.getStatus() == BookingStatus.REFUND_INITIATED) {
            log.info("Payment webhook idempotent replay: booking {} is already REFUND_INITIATED", bookingId);
            return PaymentWebhookResponse.builder()
                    .bookingId(bookingId)
                    .status(BookingStatus.REFUND_INITIATED)
                    .paymentReference(request.getPaymentReference())
                    .message("Booking was already marked as REFUND_INITIATED (idempotent replay)")
                    .timestamp(Instant.now())
                    .build();
        }

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return PaymentWebhookResponse.builder()
                    .bookingId(bookingId)
                    .status(BookingStatus.CANCELLED)
                    .paymentReference(request.getPaymentReference())
                    .message("Booking was previously cancelled")
                    .timestamp(Instant.now())
                    .build();
        }

        // 2. Handle payment failure from payment gateway
        if (request.getStatus().equalsIgnoreCase("FAILED")) {
            log.warn("Payment failed for booking {}. Cancelling reservation.", bookingId);
            booking.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(booking);

            releaseSeatsForBooking(booking);
            seatMapCacheService.evictSeatMap(booking.getEvent().getId());

            return PaymentWebhookResponse.builder()
                    .bookingId(bookingId)
                    .status(BookingStatus.CANCELLED)
                    .paymentReference(request.getPaymentReference())
                    .message("Payment failed. Booking cancelled and seats released.")
                    .timestamp(Instant.now())
                    .build();
        }

        // 3. Sort requested seat IDs to prevent deadlocks
        List<UUID> sortedSeatIds = booking.getBookingSeats().stream()
                .map(BookingSeat::getSeat)
                .map(Seat::getId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        // Pessimistically lock seats for verification
        List<Seat> seats = seatRepository.findAllByIdInWithPessimisticLock(sortedSeatIds);

        Instant now = Instant.now();
        boolean isBookingExpired = booking.getStatus() == BookingStatus.EXPIRED || booking.getExpiresAt().isBefore(now);
        boolean seatsInvalid = false;

        for (Seat seat : seats) {
            boolean isLockedByThisUser = seat.getStatus() == SeatStatus.LOCKED
                    && seat.getLockedByUserId() != null
                    && seat.getLockedByUserId().equals(booking.getUserId());

            boolean isLockStillUnexpired = seat.getLockExpiresAt() != null
                    && seat.getLockExpiresAt().isAfter(now);

            if (!isLockedByThisUser || !isLockStillUnexpired) {
                seatsInvalid = true;
                log.warn("Seat {} is no longer validly locked by user {} (status={}, lockedBy={}, expiresAt={})",
                        seat.getSeatNumber(), booking.getUserId(), seat.getStatus(), seat.getLockedByUserId(), seat.getLockExpiresAt());
                break;
            }
        }

        // 4. Edge Case: Payment at 10:01 Boundary (Race Condition)
        // If the booking expired or any seat was released/overwritten by the sweeper or claimed by another user:
        // DO NOT overwrite the new user's lock! Mark the late booking as REFUND_INITIATED.
        if (isBookingExpired || seatsInvalid) {
            log.warn("10:01 Boundary race condition triggered for booking {}. Marking REFUND_INITIATED.", bookingId);
            booking.setStatus(BookingStatus.REFUND_INITIATED);
            bookingRepository.save(booking);

            return PaymentWebhookResponse.builder()
                    .bookingId(bookingId)
                    .status(BookingStatus.REFUND_INITIATED)
                    .paymentReference(request.getPaymentReference())
                    .message("Payment arrived after reservation window expired or seat was re-allocated. Booking marked as REFUND_INITIATED.")
                    .timestamp(Instant.now())
                    .build();
        }

        // 5. Success Path: All seats validly held. Confirm booking and transition seats to BOOKED
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.BOOKED);
            seat.setLockExpiresAt(null); // Booked permanently
        }
        seatRepository.saveAll(seats);

        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        // Clear any residual Redis gatekeeper locks
        redisLockService.deleteSeatLocks(sortedSeatIds);

        // Invalidate live seat map cache
        seatMapCacheService.evictSeatMap(booking.getEvent().getId());

        log.info("Booking {} successfully CONFIRMED for payment ref {}", bookingId, request.getPaymentReference());

        return PaymentWebhookResponse.builder()
                .bookingId(bookingId)
                .status(BookingStatus.CONFIRMED)
                .paymentReference(request.getPaymentReference())
                .message("Payment confirmed and seats booked successfully")
                .timestamp(Instant.now())
                .build();
    }

    private void releaseSeatsForBooking(Booking booking) {
        List<UUID> seatIds = booking.getBookingSeats().stream()
                .map(BookingSeat::getSeat)
                .map(Seat::getId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        List<Seat> seats = seatRepository.findAllByIdInWithPessimisticLock(seatIds);
        for (Seat seat : seats) {
            if (seat.getStatus() == SeatStatus.LOCKED && booking.getUserId().equals(seat.getLockedByUserId())) {
                seat.setStatus(SeatStatus.AVAILABLE);
                seat.setLockedByUserId(null);
                seat.setLockExpiresAt(null);
            }
        }
        seatRepository.saveAll(seats);
        redisLockService.deleteSeatLocks(seatIds);
    }
}
