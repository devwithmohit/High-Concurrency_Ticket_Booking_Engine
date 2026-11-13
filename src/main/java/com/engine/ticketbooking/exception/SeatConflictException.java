package com.engine.ticketbooking.exception;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
public class SeatConflictException extends RuntimeException {

    private final List<UUID> unavailableSeatIds;

    public SeatConflictException(String message) {
        super(message);
        this.unavailableSeatIds = Collections.emptyList();
    }

    public SeatConflictException(String message, List<UUID> unavailableSeatIds) {
        super(message);
        this.unavailableSeatIds = unavailableSeatIds != null ? unavailableSeatIds : Collections.emptyList();
    }
}
