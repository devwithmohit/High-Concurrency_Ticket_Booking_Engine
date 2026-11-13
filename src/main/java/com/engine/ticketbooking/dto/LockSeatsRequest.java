package com.engine.ticketbooking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockSeatsRequest {

    @NotNull(message = "eventId is required")
    private UUID eventId;

    @NotEmpty(message = "seatIds cannot be empty")
    @Size(max = 10, message = "Cannot reserve more than 10 seats at once")
    private List<UUID> seatIds;
}
