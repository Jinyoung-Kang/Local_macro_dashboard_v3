package com.macrodash.read;

import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComputedCacheTest {

    private final StoreRepository repository = mock(StoreRepository.class);
    private final Instant t0 = Instant.parse("2026-10-03T00:00:00Z");

    private ComputedCache cacheAt(Instant now) {
        return new ComputedCache(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("같은 저장본 버전이면 두 번째 요청은 계산하지 않고 같은 객체를 돌려준다")
    void sameVersionIsServedFromCache() {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = cacheAt(t0.plusSeconds(1));
        AtomicInteger computed = new AtomicInteger();

        Map<String, Object> first = cache.get("k", () -> { computed.incrementAndGet(); return new HashMap<>(); });
        Map<String, Object> second = cache.get("k", () -> { computed.incrementAndGet(); return new HashMap<>(); });

        assertThat(computed.get()).isEqualTo(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("어느 저장본이든 새로 수집되면(최신 수집 시각이 바뀌면) 다시 계산한다")
    void newCollectionInvalidates() {
        when(repository.latestCollectedAt()).thenReturn(t0, t0, t0.plusSeconds(300));
        ComputedCache cache = cacheAt(t0.plusSeconds(1));
        AtomicInteger computed = new AtomicInteger();

        cache.get("k", computed::incrementAndGet);
        cache.get("k", computed::incrementAndGet);
        cache.get("k", computed::incrementAndGet);

        assertThat(computed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("60초가 지나면 저장본이 그대로여도 다시 계산한다 — ageSeconds·stale 같은 '지금 기준' 값 때문")
    void expiresAfterMaxAge() {
        when(repository.latestCollectedAt()).thenReturn(t0);
        AtomicInteger computed = new AtomicInteger();
        Clock[] now = {Clock.fixed(t0, ZoneOffset.UTC)};
        ComputedCache cache = new ComputedCache(repository, new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0].instant(); }
        });

        cache.get("k", computed::incrementAndGet);
        now[0] = Clock.fixed(t0.plusSeconds(ComputedCache.MAX_AGE_SECONDS - 1), ZoneOffset.UTC);
        cache.get("k", computed::incrementAndGet);
        now[0] = Clock.fixed(t0.plusSeconds(ComputedCache.MAX_AGE_SECONDS + 1), ZoneOffset.UTC);
        cache.get("k", computed::incrementAndGet);

        assertThat(computed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("저장본이 하나도 없거나 버전 조회가 실패하면 캐시를 거치지 않고 계산만 한다")
    void bypassesWithoutVersion() {
        when(repository.latestCollectedAt()).thenReturn(null);
        ComputedCache cache = cacheAt(t0);
        AtomicInteger computed = new AtomicInteger();

        cache.get("k", computed::incrementAndGet);
        cache.get("k", computed::incrementAndGet);

        assertThat(computed.get()).isEqualTo(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("계산이 예외를 던지면 아무것도 저장하지 않고 예외를 그대로 올린다")
    void exceptionsAreNotCached() {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = cacheAt(t0);

        assertThatThrownBy(() -> cache.get("k", () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("항목 수는 상한까지만 — 가장 오래 안 쓴 것부터 버린다")
    void boundedByEntryCount() {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = cacheAt(t0);
        for (int i = 0; i < ComputedCache.MAX_ENTRIES + 10; i++) {
            cache.get("k" + i, () -> 1);
        }
        assertThat(cache.size()).isEqualTo(ComputedCache.MAX_ENTRIES);
    }

    @Test
    @Disabled("QA-131: 캐시에 없는 서로 다른 키의 계산이 요청 수만큼 동시에 돕니다 — 상한이 없습니다")
    @DisplayName("QA-131 서로 다른 키의 다시 계산은 동시에 상한(코어 절반, 최소 1)까지만 돈다 — 넘친 요청은 429")
    void concurrentMissesAreBounded() throws Exception {
        // QA 스택 측정(cache-churn.js): 캐시에 없는 서로 다른 조합을 초당 50건 보내자 /api/health도 20초 안에 답하지
        // 못했습니다. 캐시를 놓친 계산은 요청 스레드에서 상한 없이 바로 돌기 때문입니다.
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = cacheAt(t0);
        int bound = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int callers = Math.max(32, bound * 4);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String key = "scorecard:" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return cache.get(key, () -> {
                        maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(100); // 비싼 계산 흉내(저장본 훑기)
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            running.decrementAndGet();
                        }
                        return key;
                    });
                } catch (RuntimeException busy) {
                    return null; // 넘친 요청은 거절돼도 됨(429)
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxRunning.get()).as("동시에 돈 계산 수").isLessThanOrEqualTo(bound);
    }
}
