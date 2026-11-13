package com.engine.ticketbooking.repository;

import com.engine.ticketbooking.entity.Seat;
import com.engine.ticketbooking.entity.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    /**
     * Layer 2 (Source of Truth - Postgres):
     * Pessimistic Write Lock (SELECT ... FOR UPDATE) to lock seats inside a transaction.
     * Callers must pass seatIds pre-sorted in ascending UUID order to prevent deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds ORDER BY s.id ASC")
    List<Seat> findAllByIdInWithPessimisticLock(@Param("seatIds") Collection<UUID> seatIds);

    /**
     * Lazy check: Fetches seats that are either marked AVAILABLE or have an expired lock.
     */
    @Query("SELECT s FROM Seat s WHERE s.event.id = :eventId AND (s.status = com.engine.ticketbooking.entity.SeatStatus.AVAILABLE OR (s.status = com.engine.ticketbooking.entity.SeatStatus.LOCKED AND s.lockExpiresAt < :now))")
    List<Seat> findAvailableSeatsByEventId(@Param("eventId") UUID eventId, @Param("now") Instant now);

    List<Seat> findByEventId(UUID eventId);

    Optional<Seat> findByEventIdAndSeatNumber(UUID eventId, String seatNumber);

    /**
     * Used by active sweeper to find expired locks.
     */
    List<Seat> findByStatusAndLockExpiresAtBefore(SeatStatus status, Instant now);

    /**
     * Active sweeper batch update to release expired seat locks back to AVAILABLE.
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = com.engine.ticketbooking.entity.SeatStatus.AVAILABLE, s.lockedByUserId = NULL, s.lockExpiresAt = NULL WHERE s.status = com.engine.ticketbooking.entity.SeatStatus.LOCKED AND s.lockExpiresAt < :now")
    int releaseExpiredLocks(@Param("now") Instant now);
}
