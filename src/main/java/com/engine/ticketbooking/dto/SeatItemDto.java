package com.engine.ticketbooking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatItemDto {
    private String seatNumber;  // e.g. "A1", "BOX-1"
    private String category;    // e.g. "VIP", "REGULAR"
    private BigDecimal price;
}
