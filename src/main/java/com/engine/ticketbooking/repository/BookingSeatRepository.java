package com.engine.ticketbooking.repository;

import com.engine.ticketbooking.entity.BookingSeat;
import com.engine.ticketbooking.entity.BookingSeatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {

    @Query("SELECT bs FROM BookingSeat bs WHERE bs.id.bookingId = :bookingId")
    List<BookingSeat> findByBookingId(@Param("bookingId") UUID bookingId);

    @Query("SELECT bs FROM BookingSeat bs WHERE bs.id.seatId = :seatId")
    List<BookingSeat> findBySeatId(@Param("seatId") UUID seatId);
}
