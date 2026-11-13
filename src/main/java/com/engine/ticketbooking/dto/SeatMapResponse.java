package com.engine.ticketbooking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatMapResponse implements Serializable {
    private UUID eventId;
    private String eventTitle;
    private String venue;
    private Instant eventTime;
    private int totalSeats;
    private int availableSeats;
    private int lockedSeats;
    private int bookedSeats;
    private List<SeatDto> seats;
    private Instant generatedAt;
}
