package com.engine.ticketbooking.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "booking_seats",
    indexes = {
        @Index(name = "idx_booking_seats_seat_id", columnList = "seat_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class BookingSeat {

    @EmbeddedId
    private BookingSeatId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("bookingId")
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("seatId")
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    public BookingSeat(Booking booking, Seat seat) {
        this.booking = booking;
        this.seat = seat;
        this.id = new BookingSeatId(booking.getId(), seat.getId());
    }
}
