package com.engine.ticketbooking.scheduler;

import com.engine.ticketbooking.entity.Booking;
import com.engine.ticketbooking.entity.BookingSeat;
import com.engine.ticketbooking.entity.BookingStatus;
import com.engine.ticketbooking.entity.Seat;
import com.engine.ticketbooking.entity.SeatStatus;
import com.engine.ticketbooking.repository.BookingRepository;
import com.engine.ticketbooking.repository.SeatRepository;
import com.engine.ticketbooking.service.RedisLockService;
import com.engine.ticketbooking.service.SeatMapCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredLockSweeper {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final RedisLockService redisLockService;
    private final SeatMapCacheService seatMapCacheService;

    /**
     * Active background sweeper running every 30 seconds.
     * 1. Scans for expired PENDING bookings where expires_at < NOW().
     * 2. Transitions booking status to EXPIRED.
     * 3. Reverts associated seats from LOCKED back to AVAILABLE (clearing locked_by_user_id & lock_expires_at).
     * 4. Scans for any orphaned LOCKED seats whose lock_expires_at < NOW() and releases them.
     * 5. Clears Redis distributed locks and invalidates Redis seat map caches for affected events.
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    @Transactional
    public void sweepExpiredLocks() {
        Instant now = Instant.now();
        Set<UUID> affectedEventIds = new HashSet<>();
        List<UUID> releasedSeatIds = new ArrayList<>();

        try {
            // 1. Process expired PENDING bookings
            List<Booking> expiredBookings = bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, now);

            if (!expiredBookings.isEmpty()) {
                log.info("Found {} expired PENDING booking(s) to process", expiredBookings.size());

                for (Booking booking : expiredBookings) {
                    booking.setStatus(BookingStatus.EXPIRED);
                    affectedEventIds.add(booking.getEvent().getId());

                    for (BookingSeat bookingSeat : booking.getBookingSeats()) {
                        Seat seat = bookingSeat.getSeat();
                        // Only release if seat is still LOCKED and belongs to this user
                        if (seat.getStatus() == SeatStatus.LOCKED &&
                            booking.getUserId().equals(seat.getLockedByUserId())) {

                            seat.setStatus(SeatStatus.AVAILABLE);
                            seat.setLockedByUserId(null);
                            seat.setLockExpiresAt(null);
                            seatRepository.save(seat);

                            releasedSeatIds.add(seat.getId());
                        }
                    }
                }
                bookingRepository.saveAll(expiredBookings);
            }

            // 2. Process any orphaned LOCKED seats whose lock_expires_at < NOW()
            List<Seat> orphanedExpiredSeats = seatRepository.findByStatusAndLockExpiresAtBefore(SeatStatus.LOCKED, now);

            if (!orphanedExpiredSeats.isEmpty()) {
                log.info("Found {} orphaned expired LOCKED seat(s) to release", orphanedExpiredSeats.size());

                for (Seat seat : orphanedExpiredSeats) {
                    seat.setStatus(SeatStatus.AVAILABLE);
                    seat.setLockedByUserId(null);
                    seat.setLockExpiresAt(null);
                    releasedSeatIds.add(seat.getId());
                    affectedEventIds.add(seat.getEvent().getId());
                }
                seatRepository.saveAll(orphanedExpiredSeats);
            }

            // 3. Clear Redis distributed locks for all released seats
            if (!releasedSeatIds.isEmpty()) {
                redisLockService.deleteSeatLocks(releasedSeatIds);
            }

            // 4. Invalidate Redis seat map cache for all affected events
            for (UUID eventId : affectedEventIds) {
                seatMapCacheService.evictSeatMap(eventId);
            }

            if (!expiredBookings.isEmpty() || !orphanedExpiredSeats.isEmpty()) {
                log.info("Sweeper completed: expired {} bookings, released {} seats across {} events",
                        expiredBookings.size(), releasedSeatIds.size(), affectedEventIds.size());
            }

        } catch (Exception e) {
            log.error("Error occurred during expired lock sweeper execution: {}", e.getMessage(), e);
        }
    }
}
