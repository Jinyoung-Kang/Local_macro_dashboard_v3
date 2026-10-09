package com.macrodash.feature.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키마다 일정 시간 안의 요청 수 상한 — 가입·재설정 메일 요청이 메일 폭탄이나 계정 탐색에 쓰이지 않게(메모리, 한 인스턴스).
 */
final class RateLimit {

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    RateLimit(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** 허용되면 기록하고 true. 상한을 넘으면 false. */
    boolean allow(String key) {
        long now = clock.millis();
        long since = now - window.toMillis();
        if (hits.size() > 10_000) {
            hits.values().removeIf(deque -> deque.isEmpty() || deque.peekLast() < since);
        }
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < since) {
                deque.pollFirst();
            }
            if (deque.size() >= max) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
