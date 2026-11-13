package com.engine.ticketbooking.service;

import com.engine.ticketbooking.dto.LockSeatsRequest;
import com.engine.ticketbooking.dto.LockSeatsResponse;
import com.engine.ticketbooking.entity.Booking;
import com.engine.ticketbooking.entity.BookingSeat;
import com.engine.ticketbooking.entity.BookingStatus;
import com.engine.ticketbooking.entity.Event;
import com.engine.ticketbooking.entity.Seat;
import com.engine.ticketbooking.entity.SeatStatus;
import com.engine.ticketbooking.exception.SeatConflictException;
import com.engine.ticketbooking.repository.BookingRepository;
import com.engine.ticketbooking.repository.EventRepository;
import com.engine.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final long GATEKEEPER_LOCK_TIMEOUT_MS = 10000; // 10s Redis gatekeeper TTL
    private static final Duration SEAT_LOCK_DURATION = Duration.ofMinutes(10); // 10 mins reservation window

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final RedisLockService redisLockService;
    private final SeatMapCacheService seatMapCacheService;

    /**
     * Two-Phase Seat Reservation:
     * 1. Check Idempotency-Key.
     * 2. Deadlock prevention: sort requested seatIds in ascending order.
     * 3. Layer 1: Acquire Redis distributed lock (SET key value NX PX 10000).
     * 4. Layer 2: Inside DB transaction, fetch seats with SELECT ... FOR UPDATE, verify availability.
     * 5. Transition to LOCKED, set locked_by_user_id and lock_expires_at (+10m), save Booking (PENDING).
     * 6. Release Layer 1 Redis lock and evict seat map cache.
     */
    public LockSeatsResponse lockSeats(UUID userId, String idempotencyKey, LockSeatsRequest request) {
        // Step 1: Enforce Idempotency-Key check
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is mandatory for seat reservation");
        }
        if (idempotencyKey.length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 64 characters");
        }

        Optional<Booking> existingBookingOpt = bookingRepository.findByIdempotencyKey(idempotencyKey);
        if (existingBookingOpt.isPresent()) {
            Booking existing = existingBookingOpt.get();
            log.info("Idempotent replay detected for key: {}, bookingId: {}", idempotencyKey, existing.getId());
            return buildResponse(existing, "Idempotent response: booking already processed with this key");
        }

        // Step 2: Multi-Seat Deadlock Prevention
        // Sort requested seatIds in strict ascending UUID string order
        List<UUID> sortedSeatIds = request.getSeatIds().stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        if (sortedSeatIds.isEmpty()) {
            throw new IllegalArgumentException("At least one seat must be selected");
        }

        String lockValue = userId + ":" + UUID.randomUUID();

        // Step 3: Layer 1 (Fast Gatekeeper - Redis)
        redisLockService.acquireSeatLocks(sortedSeatIds, lockValue, GATEKEEPER_LOCK_TIMEOUT_MS);

        try {
            // Step 4 & 5: Layer 2 (Source of Truth - PostgreSQL inside Transaction)
            Booking booking = executeDatabaseReservation(userId, idempotencyKey, request.getEventId(), sortedSeatIds);

            // Invalidate Redis Seat Map cache so other clients immediately see updated statuses
            seatMapCacheService.evictSeatMap(request.getEventId());

            return buildResponse(booking, "Seats successfully reserved for 10 minutes");

        } finally {
            // Always release the Layer 1 Redis gatekeeper lock
            redisLockService.releaseSeatLocks(sortedSeatIds, lockValue);
        }
    }

    /**
     * Executes Layer 2 transactional database reservation with Pessimistic Locking.
     */
    @Transactional
    public Booking executeDatabaseReservation(UUID userId, String idempotencyKey, UUID eventId, List<UUID> sortedSeatIds) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found with ID: " + eventId));

        // Fetch seats with Pessimistic Write Lock (SELECT ... FOR UPDATE) ordered by ID ASC
        List<Seat> lockedSeats = seatRepository.findAllByIdInWithPessimisticLock(sortedSeatIds);

        if (lockedSeats.size() != sortedSeatIds.size()) {
            throw new IllegalArgumentException("One or more requested seat IDs do not exist");
        }

        Instant now = Instant.now();
        List<UUID> unavailableSeatIds = new ArrayList<>();

        for (Seat seat : lockedSeats) {
            if (!seat.getEvent().getId().equals(eventId)) {
                throw new IllegalArgumentException("Seat " + seat.getSeatNumber() + " does not belong to event " + eventId);
            }

            // Lazy Check: Seat is available if status is AVAILABLE, or LOCKED with expired lock_expires_at
            if (!seat.isAvailableAt(now)) {
                unavailableSeatIds.add(seat.getId());
            }
        }

        if (!unavailableSeatIds.isEmpty()) {
            log.warn("Reservation conflict: seats unavailable: {}", unavailableSeatIds);
            throw new SeatConflictException(
                    "One or more requested seats are currently locked or already booked",
                    unavailableSeatIds
            );
        }

        Instant expiresAt = now.plus(SEAT_LOCK_DURATION);
        BigDecimal totalAmount = BigDecimal.ZERO;

        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder()
                .id(bookingId)
                .userId(userId)
                .event(event)
                .status(BookingStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .expiresAt(expiresAt)
                .totalAmount(BigDecimal.ZERO)
                .build();

        for (Seat seat : lockedSeats) {
            seat.setStatus(SeatStatus.LOCKED);
            seat.setLockedByUserId(userId);
            seat.setLockExpiresAt(expiresAt);

            booking.addSeat(seat);
            totalAmount = totalAmount.add(seat.getPrice());
        }

        booking.setTotalAmount(totalAmount);

        seatRepository.saveAll(lockedSeats);
        Booking savedBooking = bookingRepository.save(booking);

        log.info("Successfully reserved {} seats for user {} under bookingId: {}, expiresAt: {}",
                lockedSeats.size(), userId, savedBooking.getId(), expiresAt);

        return savedBooking;
    }

    private LockSeatsResponse buildResponse(Booking booking, String message) {
        List<UUID> seatIds = booking.getBookingSeats().stream()
                .map(BookingSeat::getSeat)
                .map(Seat::getId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        return LockSeatsResponse.builder()
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .eventId(booking.getEvent().getId())
                .seatIds(seatIds)
                .totalAmount(booking.getTotalAmount())
                .status(booking.getStatus())
                .idempotencyKey(booking.getIdempotencyKey())
                .expiresAt(booking.getExpiresAt())
                .createdAt(booking.getCreatedAt())
                .message(message)
                .build();
    }
}
