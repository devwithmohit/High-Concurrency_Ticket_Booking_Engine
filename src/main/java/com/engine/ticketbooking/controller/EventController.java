package com.engine.ticketbooking.controller;

import com.engine.ticketbooking.dto.CreateEventRequest;
import com.engine.ticketbooking.dto.EventResponse;
import com.engine.ticketbooking.dto.SeatMapResponse;
import com.engine.ticketbooking.service.EventService;
import com.engine.ticketbooking.service.SeatMapCacheService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final SeatMapCacheService seatMapCacheService;

    /**
     * Admin API: Create event and bulk generate seats.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventResponse response = eventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Public API: List all events.
     */
    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    /**
     * Public API: Get event details by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEventById(@PathVariable UUID id) {
        return ResponseEntity.ok(eventService.getEvent(id));
    }

    /**
     * Public Seat Map API: Live seat status cached in Redis with lazy lock expiration check.
     */
    @GetMapping("/{id}/seats")
    public ResponseEntity<SeatMapResponse> getSeatMap(@PathVariable UUID id) {
        SeatMapResponse seatMap = seatMapCacheService.getSeatMap(id);
        return ResponseEntity.ok(seatMap);
    }

    /**
     * Admin API: Invalidate Redis seat map cache explicitly.
     */
    @PostMapping("/{id}/cache/invalidate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> invalidateCache(@PathVariable UUID id) {
        seatMapCacheService.evictSeatMap(id);
        return ResponseEntity.ok(Map.of(
                "eventId", id,
                "status", "CACHE_INVALIDATED",
                "message", "Seat map cache successfully evicted from Redis"
        ));
    }
}
