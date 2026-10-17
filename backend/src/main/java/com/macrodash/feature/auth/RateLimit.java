package com.macrodash.feature.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키마다 일정 시간 안의 요청 수 상한 — 가입·재설정 메일 요청이 메일 폭탄이나 계정 탐색에 쓰이지 않게(메모리, 한 인스턴스).
 *
 * <p>기억하는 키는 {@value #MAX_TRACKED}개까지입니다(QA-073 — {@link LoginThrottle}의 QA-019·055와 같은 규칙).
 * 예전에는 상한이 없어 이메일만 바꿔 보내면 키가 요청마다 늘었고, 1만 개를 넘은 뒤로는 요청마다 맵 전체를 훑었습니다.
 * <ul>
 *   <li>꽉 차면 처음 보는 키는 <b>기록하지 않고 거절</b>합니다. 이미 아는 키는 그대로 셉니다. 꽉 찬 동안은 처음 오는
 *       정상 요청도 거절되는 것이 대가입니다(호출하는 쪽이 주소 키를 먼저 보므로, 채우려면 서로 다른 접속 주소가 그만큼 필요).
 *       크기 확인과 기록 사이가 원자적이지 않아 동시 요청 수만큼은 넘을 수 있습니다.</li>
 *   <li>정리(창이 지난 키 삭제)는 꽉 찬 동안 {@code PRUNE_EVERY}에 한 번만 합니다. 요청마다 훑으면 그것이 CPU를 태웁니다.</li>
 *   <li>키마다의 기록은 고치지 않는 값으로 통째로 바꿔 끼웁니다({@link ConcurrentHashMap#compute}). 그래서 정리가 잠금 없이
 *       읽어도 쓰는 도중의 기록을 보지 않고, 그 사이 바뀐 기록은 지우지 않습니다(값이 같을 때만 지움).</li>
 * </ul>
 */
final class RateLimit {

    /** 기억하는 키의 상한. */
    static final int MAX_TRACKED = 10_000;
    /** 꽉 찬 동안 정리하는 간격. */
    private static final long PRUNE_EVERY_MS = Duration.ofSeconds(10).toMillis();

    /** 창 안의 요청 시각(밀리초, 오름차순). 바꾸지 않고 새로 만들어 끼웁니다. */
    private record Hits(long[] at) {
        boolean olderThan(long since) {
            return at.length == 0 || at[at.length - 1] < since;
        }
    }

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Hits> hits = new ConcurrentHashMap<>();
    private volatile long lastPrune = 0;

    RateLimit(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** 허용되면 기록하고 true. 상한을 넘었거나, 기록이 꽉 찬 동안 처음 보는 키면 false(기록하지 않음). */
    boolean allow(String key) {
        long now = clock.millis();
        long since = now - window.toMillis();
        pruneIfFull(now, since);
        if (hits.size() >= MAX_TRACKED && !hits.containsKey(key)) {
            return false;
        }
        boolean[] allowed = {false};
        hits.compute(key, (k, old) -> {
            long[] kept = old == null ? new long[0]
                    : Arrays.stream(old.at()).filter(at -> at >= since).toArray();
            if (kept.length >= max) {
                return new Hits(kept);
            }
            allowed[0] = true;
            long[] next = Arrays.copyOf(kept, kept.length + 1);
            next[kept.length] = now;
            return new Hits(next);
        });
        return allowed[0];
    }

    private void pruneIfFull(long now, long since) {
        if (hits.size() < MAX_TRACKED || now < lastPrune + PRUNE_EVERY_MS) {
            return;
        }
        lastPrune = now;
        hits.values().removeIf(entry -> entry.olderThan(since));
    }
}
