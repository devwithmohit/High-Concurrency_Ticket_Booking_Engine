package com.engine.ticketbooking.service;

import com.engine.ticketbooking.dto.SeatDto;
import com.engine.ticketbooking.dto.SeatMapResponse;
import com.engine.ticketbooking.entity.Event;
import com.engine.ticketbooking.entity.Seat;
import com.engine.ticketbooking.entity.SeatStatus;
import com.engine.ticketbooking.repository.EventRepository;
import com.engine.ticketbooking.repository.SeatRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatMapCacheService {

    private static final String SEAT_MAP_KEY_PREFIX = "seatmap:event:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(UUID eventId) {
        String cacheKey = SEAT_MAP_KEY_PREFIX + eventId;

        // 1. Try fetching from Redis cache
        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                log.debug("Seat map cache HIT for eventId: {}", eventId);
                return objectMapper.readValue(cachedJson, SeatMapResponse.class);
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve seat map from Redis for eventId: {}, falling back to DB. Error: {}", eventId, e.getMessage());
        }

        log.debug("Seat map cache MISS for eventId: {}", eventId);

        // 2. Cache miss: load from PostgreSQL
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found with ID: " + eventId));

        List<Seat> seats = seatRepository.findByEventId(eventId);
        Instant now = Instant.now();

        int availableCount = 0;
        int lockedCount = 0;
        int bookedCount = 0;

        List<SeatDto> seatDtos = new ArrayList<>(seats.size());

        for (Seat seat : seats) {
            SeatStatus effectiveStatus;
            if (seat.getStatus() == SeatStatus.BOOKED) {
                effectiveStatus = SeatStatus.BOOKED;
                bookedCount++;
            } else if (seat.isAvailableAt(now)) {
                // Lazy check: if status is AVAILABLE or LOCKED with lock_expires_at < NOW()
                effectiveStatus = SeatStatus.AVAILABLE;
                availableCount++;
            } else {
                effectiveStatus = SeatStatus.LOCKED;
                lockedCount++;
            }

            seatDtos.add(SeatDto.builder()
                    .id(seat.getId())
                    .seatNumber(seat.getSeatNumber())
                    .category(seat.getCategory())
                    .price(seat.getPrice())
                    .status(effectiveStatus)
                    .build());
        }

        // Sort seats naturally by seat number (e.g. A1, A2, ...)
        seatDtos.sort(Comparator.comparing(SeatDto::getSeatNumber));

        SeatMapResponse response = SeatMapResponse.builder()
                .eventId(event.getId())
                .eventTitle(event.getTitle())
                .venue(event.getVenue())
                .eventTime(event.getEventTime())
                .totalSeats(seats.size())
                .availableSeats(availableCount)
                .lockedSeats(lockedCount)
                .bookedSeats(bookedCount)
                .seats(seatDtos)
                .generatedAt(now)
                .build();

        // 3. Populate Redis cache
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
            log.debug("Populated seat map cache for eventId: {} with TTL: {}s", eventId, CACHE_TTL.toSeconds());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SeatMapResponse for eventId: {}", eventId, e);
        } catch (Exception e) {
            log.warn("Failed to write seat map to Redis for eventId: {}. Error: {}", eventId, e.getMessage());
        }

        return response;
    }

    /**
     * Cache Invalidation Trigger: evicts the cached seat map from Redis
     * whenever seat statuses change (locking, booking, expiration, cancellation, admin updates).
     */
    public void evictSeatMap(UUID eventId) {
        String cacheKey = SEAT_MAP_KEY_PREFIX + eventId;
        try {
            Boolean deleted = stringRedisTemplate.delete(cacheKey);
            log.info("Evicted seat map cache for eventId: {}. Deleted: {}", eventId, deleted);
        } catch (Exception e) {
            log.warn("Failed to evict seat map cache for eventId: {}. Error: {}", eventId, e.getMessage());
        }
    }
}
