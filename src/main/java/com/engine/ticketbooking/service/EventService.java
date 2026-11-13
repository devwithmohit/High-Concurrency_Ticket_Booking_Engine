package com.engine.ticketbooking.service;

import com.engine.ticketbooking.dto.CreateEventRequest;
import com.engine.ticketbooking.dto.EventResponse;
import com.engine.ticketbooking.dto.SeatGeneratorConfig;
import com.engine.ticketbooking.dto.SeatItemDto;
import com.engine.ticketbooking.entity.Event;
import com.engine.ticketbooking.entity.Seat;
import com.engine.ticketbooking.entity.SeatStatus;
import com.engine.ticketbooking.repository.EventRepository;
import com.engine.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatMapCacheService seatMapCacheService;

    @Transactional
    public EventResponse createEvent(CreateEventRequest request) {
        UUID eventId = UUID.randomUUID();

        List<Seat> seats = new ArrayList<>();
        Set<String> uniqueSeatNumbers = new HashSet<>();

        Event event = Event.builder()
                .id(eventId)
                .title(request.getTitle())
                .venue(request.getVenue())
                .eventTime(request.getEventTime())
                .totalSeats(0) // Will update after seat generation
                .build();

        // 1. Process Range-based seat generators (e.g. Row A from 1 to 50)
        if (request.getSeatGenerators() != null) {
            for (SeatGeneratorConfig gen : request.getSeatGenerators()) {
                String row = gen.getRow().trim().toUpperCase();
                int start = Math.min(gen.getStartNumber(), gen.getEndNumber());
                int end = Math.max(gen.getStartNumber(), gen.getEndNumber());
                String category = gen.getCategory() != null ? gen.getCategory() : "GENERAL";
                BigDecimal price = gen.getPrice() != null ? gen.getPrice() : BigDecimal.valueOf(50.00);

                for (int i = start; i <= end; i++) {
                    String seatNumber = row + i;
                    if (uniqueSeatNumbers.add(seatNumber)) {
                        seats.add(Seat.builder()
                                .id(UUID.randomUUID())
                                .event(event)
                                .seatNumber(seatNumber)
                                .category(category)
                                .price(price)
                                .status(SeatStatus.AVAILABLE)
                                .build());
                    }
                }
            }
        }

        // 2. Process Grid-based generator (e.g. 5 rows [A-E], 10 cols [1-10])
        if (request.getGridRows() != null && request.getGridRows() > 0 &&
            request.getGridCols() != null && request.getGridCols() > 0) {

            String category = request.getDefaultCategory() != null ? request.getDefaultCategory() : "GENERAL";
            BigDecimal price = request.getDefaultPrice() != null ? request.getDefaultPrice() : BigDecimal.valueOf(50.00);

            for (int r = 0; r < request.getGridRows(); r++) {
                char rowChar = (char) ('A' + r);
                for (int c = 1; c <= request.getGridCols(); c++) {
                    String seatNumber = "" + rowChar + c;
                    if (uniqueSeatNumbers.add(seatNumber)) {
                        seats.add(Seat.builder()
                                .id(UUID.randomUUID())
                                .event(event)
                                .seatNumber(seatNumber)
                                .category(category)
                                .price(price)
                                .status(SeatStatus.AVAILABLE)
                                .build());
                    }
                }
            }
        }

        // 3. Process Explicit Seat List
        if (request.getExplicitSeats() != null) {
            for (SeatItemDto item : request.getExplicitSeats()) {
                String seatNumber = item.getSeatNumber().trim().toUpperCase();
                if (uniqueSeatNumbers.add(seatNumber)) {
                    seats.add(Seat.builder()
                            .id(UUID.randomUUID())
                            .event(event)
                            .seatNumber(seatNumber)
                            .category(item.getCategory() != null ? item.getCategory() : "GENERAL")
                            .price(item.getPrice() != null ? item.getPrice() : BigDecimal.valueOf(50.00))
                            .status(SeatStatus.AVAILABLE)
                            .build());
                }
            }
        }

        if (seats.isEmpty()) {
            throw new IllegalArgumentException("At least one seat must be generated for the event");
        }

        event.setTotalSeats(seats.size());
        Event savedEvent = eventRepository.save(event);
        seatRepository.saveAll(seats);

        // Invalidate any potential stale cache for this event
        seatMapCacheService.evictSeatMap(eventId);

        log.info("Created event: {} with {} bulk-generated seats", savedEvent.getId(), seats.size());

        return EventResponse.builder()
                .id(savedEvent.getId())
                .title(savedEvent.getTitle())
                .venue(savedEvent.getVenue())
                .eventTime(savedEvent.getEventTime())
                .totalSeats(savedEvent.getTotalSeats())
                .createdAt(savedEvent.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found with ID: " + eventId));
        return EventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .venue(event.getVenue())
                .eventTime(event.getEventTime())
                .totalSeats(event.getTotalSeats())
                .createdAt(event.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(e -> EventResponse.builder()
                        .id(e.getId())
                        .title(e.getTitle())
                        .venue(e.getVenue())
                        .eventTime(e.getEventTime())
                        .totalSeats(e.getTotalSeats())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
    }
}
