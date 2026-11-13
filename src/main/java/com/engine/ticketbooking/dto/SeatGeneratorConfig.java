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
public class SeatGeneratorConfig {
    private String row;         // e.g. "A"
    private int startNumber;    // e.g. 1
    private int endNumber;      // e.g. 50
    private String category;    // e.g. "VIP", "REGULAR"
    private BigDecimal price;   // e.g. 100.00
}
