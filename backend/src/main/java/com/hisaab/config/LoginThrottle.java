package com.hisaab.config;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits password guessing: after {@value #MAX_FAILURES} wrong passwords from one client within the window, that
 * client can't try again until the oldest of them is a window old. A right password clears its count. Covers
 * every place the app password is checked (sign-in, and linking a provider account).
 */
@Component
public class LoginThrottle {
    public static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_CLIENTS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /** How long this client must wait before trying a password again; zero when it may try now. */
    public Duration waitFor(String client) {
        return waitFor(client, Instant.now());
    }

    Duration waitFor(String client, Instant now) {
        Deque<Instant> q = failures.get(client);
        if (q == null) return Duration.ZERO;
        synchronized (q) {
            prune(q, now);
            if (q.size() < MAX_FAILURES) return Duration.ZERO;
            return Duration.between(now, q.peekFirst().plus(WINDOW)).plusSeconds(1);
        }
    }

    /** @return the client's failures within the window, this one included */
    public int failed(String client) {
        return failed(client, Instant.now());
    }

    int failed(String client, Instant now) {
        if (failures.size() > MAX_CLIENTS) failures.values().removeIf(q -> {
            synchronized (q) {
                prune(q, now);
                return q.isEmpty();
            }
        });
        Deque<Instant> q = failures.computeIfAbsent(client, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            q.addLast(now);
            return q.size();
        }
    }

    public void succeeded(String client) {
        failures.remove(client);
    }

    private static void prune(Deque<Instant> q, Instant now) {
        while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(WINDOW))) q.removeFirst();
    }
}
