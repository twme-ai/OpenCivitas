package dev.opencivitas.network;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class NetworkDeduplicator {
    private final long maximumAgeMillis;
    private final long maximumFutureSkewMillis;
    private final int capacity;
    private final LinkedHashMap<UUID, Long> seen = new LinkedHashMap<>();

    public NetworkDeduplicator(Duration maximumAge, Duration maximumFutureSkew, int capacity) {
        if (maximumAge.isNegative() || maximumAge.isZero()
                || maximumFutureSkew.isNegative() || capacity < 1) {
            throw new IllegalArgumentException("Invalid network replay limits");
        }
        maximumAgeMillis = maximumAge.toMillis();
        maximumFutureSkewMillis = maximumFutureSkew.toMillis();
        this.capacity = capacity;
    }

    public synchronized boolean accept(UUID messageId, long createdAt, long now) {
        if (messageId == null || createdAt < now - maximumAgeMillis
                || createdAt > now + maximumFutureSkewMillis || seen.containsKey(messageId)) {
            return false;
        }
        seen.put(messageId, createdAt);
        Iterator<Map.Entry<UUID, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (seen.size() <= capacity && entry.getValue() >= now - maximumAgeMillis) break;
            iterator.remove();
        }
        return true;
    }
}
