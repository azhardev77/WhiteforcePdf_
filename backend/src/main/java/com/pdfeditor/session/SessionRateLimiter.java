package com.pdfeditor.session;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRateLimiter {

    private final ConcurrentHashMap<String, Deque<Long>> requestHistory = new ConcurrentHashMap<>();
    private final SessionProperties properties;

    public SessionRateLimiter(SessionProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) {
            ip = "unknown";
        }

        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;
        int maxRequests = properties.getRateLimitPerMinute();

        Deque<Long> timestamps = requestHistory.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= maxRequests) {
                return false;
            }

            timestamps.addLast(now);
            return true;
        }
    }
}
