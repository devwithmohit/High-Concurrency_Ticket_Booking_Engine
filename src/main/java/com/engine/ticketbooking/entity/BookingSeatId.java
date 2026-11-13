package com.engine.ticketbooking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class BookingSeatId implements Serializable {

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "seat_id", nullable = false)
    private UUID seatId;
}
