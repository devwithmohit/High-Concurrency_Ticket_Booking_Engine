package com.engine.ticketbooking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_seats_event_seat_number", columnNames = {"event_id", "seat_number"})
    },
    indexes = {
        @Index(name = "idx_seats_event_id", columnList = "event_id"),
        @Index(name = "idx_seats_event_status", columnList = "event_id, status"),
        @Index(name = "idx_seats_status_expires", columnList = "status, lock_expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "seat_number", nullable = false, length = 50)
    private String seatNumber;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "locked_by_user_id")
    private UUID lockedByUserId;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;

    /**
     * Lazy check: if status is LOCKED but current time is past lockExpiresAt,
     * the seat is effectively available.
     */
    public boolean isAvailableAt(Instant now) {
        if (this.status == SeatStatus.AVAILABLE) {
            return true;
        }
        if (this.status == SeatStatus.LOCKED && this.lockExpiresAt != null && this.lockExpiresAt.isBefore(now)) {
            return true;
        }
        return false;
    }
}
