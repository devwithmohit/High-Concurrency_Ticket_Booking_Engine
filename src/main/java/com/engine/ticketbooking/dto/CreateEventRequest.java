package com.engine.ticketbooking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEventRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Venue is required")
    private String venue;

    @NotNull(message = "Event time is required")
    @Future(message = "Event time must be in the future")
    private Instant eventTime;

    // Strategy 1: Range-based generators (e.g. Row A from 1 to 50)
    private List<SeatGeneratorConfig> seatGenerators;

    // Strategy 2: Grid-based (rows: 5 [A-E], cols: 10 [1-10])
    private Integer gridRows;
    private Integer gridCols;
    private String defaultCategory;
    private BigDecimal defaultPrice;

    // Strategy 3: Explicit list of seat items
    private List<SeatItemDto> explicitSeats;
}
