package com.engine.ticketbooking.service;

import com.engine.ticketbooking.exception.SeatConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockService {

    private static final String SEAT_LOCK_PREFIX = "lock:seat:";
    private static final String UNLOCK_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> unlockScript = new DefaultRedisScript<>(UNLOCK_LUA_SCRIPT, Long.class);

    /**
     * Layer 1 (Fast Gatekeeper - Redis):
     * Acquires distributed locks for all requested seats using `SET key value NX PX 10000`.
     * Seats MUST be pre-sorted in ascending UUID order to prevent deadlocks.
     *
     * @param sortedSeatIds List of seat UUIDs sorted in ascending order
     * @param lockValue Unique token for the lock owner
     * @param lockTimeoutMs TTL for the gatekeeper lock (default 10,000ms = 10s)
     * @return List of seat IDs that were successfully locked
     */
    public List<UUID> acquireSeatLocks(List<UUID> sortedSeatIds, String lockValue, long lockTimeoutMs) {
        List<UUID> acquiredLocks = new ArrayList<>();

        for (UUID seatId : sortedSeatIds) {
            String lockKey = SEAT_LOCK_PREFIX + seatId;
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockValue,
                    Duration.ofMillis(lockTimeoutMs)
            );

            if (Boolean.TRUE.equals(acquired)) {
                acquiredLocks.add(seatId);
            } else {
                log.warn("Layer 1 Redis Lock failed for seatId: {}. Rolling back acquired locks: {}", seatId, acquiredLocks);
                // Rollback any locks acquired during this batch
                releaseSeatLocks(acquiredLocks, lockValue);
                throw new SeatConflictException(
                        "Seat is currently being reserved by another user (Redis gatekeeper rejected): " + seatId,
                        Collections.singletonList(seatId)
                );
            }
        }

        return acquiredLocks;
    }

    /**
     * Atomically releases distributed seat locks in Redis using Lua script.
     */
    public void releaseSeatLocks(List<UUID> seatIds, String lockValue) {
        for (UUID seatId : seatIds) {
            String lockKey = SEAT_LOCK_PREFIX + seatId;
            try {
                stringRedisTemplate.execute(
                        unlockScript,
                        Collections.singletonList(lockKey),
                        lockValue
                );
            } catch (Exception e) {
                log.error("Failed to release Redis lock for key: {}. Error: {}", lockKey, e.getMessage());
            }
        }
    }

    /**
     * Forcefully evicts Redis distributed locks for specified seat IDs
     * (used during sweeper cleanup and booking confirmation).
     */
    public void deleteSeatLocks(Collection<UUID> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }
        List<String> keys = seatIds.stream()
                .map(id -> SEAT_LOCK_PREFIX + id)
                .toList();
        try {
            stringRedisTemplate.delete(keys);
            log.debug("Deleted Redis locks for seats: {}", seatIds);
        } catch (Exception e) {
            log.warn("Failed to delete Redis locks for seats: {}. Error: {}", seatIds, e.getMessage());
        }
    }
}
