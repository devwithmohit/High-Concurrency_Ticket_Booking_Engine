package com.engine.ticketbooking.repository;

import com.engine.ticketbooking.entity.Booking;
import com.engine.ticketbooking.entity.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * Enforce idempotency on all booking creation and confirmation operations.
     */
    @Query("SELECT b FROM Booking b LEFT JOIN FETCH b.bookingSeats bs LEFT JOIN FETCH bs.seat WHERE b.idempotencyKey = :idempotencyKey")
    Optional<Booking> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    List<Booking> findByUserId(UUID userId);

    List<Booking> findByEventId(UUID eventId);

    /**
     * Used by active sweeper to find pending bookings that have expired.
     */
    @Query("SELECT DISTINCT b FROM Booking b LEFT JOIN FETCH b.bookingSeats bs LEFT JOIN FETCH bs.seat WHERE b.status = :status AND b.expiresAt < :now")
    List<Booking> findByStatusAndExpiresAtBefore(@Param("status") BookingStatus status, @Param("now") Instant now);

    /**
     * Pessimistic lock on booking for payment webhook confirmation race condition handling.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdWithPessimisticLock(@Param("id") UUID id);
}
