package com.macrodash.read;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    // ------------------------------------------------------------------ 동시 실행 상한(QA-131)

    /** 판정에 쓰는 기다림의 상한 — 정상이면 수 ms 안에 끝나는 일을 넉넉하게 기다립니다(시간으로 판정하지 않음). */
    private static final long GENEROUS_SECONDS = 30;

    /** 다른 스레드에서 {@code key}를 계산하며 자리를 쥐고, {@code release}가 열릴 때까지 계산을 멈춰 둡니다. */
    private static Future<Object> holdSlot(ExecutorService pool, ComputedCache cache, String key,
                                           CountDownLatch entered, CountDownLatch release) {
        return pool.submit(() -> cache.get(key, () -> {
            entered.countDown();
            awaitQuietly(release);
            return (Object) key;
        }));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(GENEROUS_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 래치를 열지 않았습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 자리를 기다리는 요청이 {@code n}건이 될 때까지(세마포어 대기열 길이) 기다립니다. */
    private static void awaitWaiting(ComputedCache cache, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS);
        while (cache.waiting() < n) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("자리를 기다리는 요청이 " + n + "건이 되지 않았습니다: " + cache.waiting());
            }
            Thread.sleep(1);
        }
    }

    /** 테스트가 시각을 옮길 수 있는 시계. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("QA-131 자리가 나지 않으면 기다리다 429(ComputeBusyException, 2초 뒤 재시도)로 거절하고 아무것도 저장하지 않는다")
    void rejectsWhenNoSlotFreesUp() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofMillis(50), 32);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdSlot(pool, cache, "a", entered, release);
        assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> cache.get("b", () -> "b"))
                .isInstanceOfSatisfying(ComputeBusyException.class, busy -> {
                    assertThat(busy.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(busy.getMessage()).isEqualTo("요청이 몰려 지금은 계산하지 못했습니다. 잠시 후 다시 시도하세요.");
                });
        assertThat(cache.size()).as("거절된 b는 저장하지 않음(a는 아직 계산 중)").isZero();

        release.countDown();
        assertThat(holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS)).isEqualTo("a");
        assertThat(cache.<String>get("c", () -> "c")).as("자리가 나면 다시 받음").isEqualTo("c");
        assertThat(cache.rejectedCount()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(2);
        pool.shutdown();
    }

    @Test
    @DisplayName("QA-131 거절 경고는 60초에 한 번으로 묶는다 — 거절이 몰려도 요청마다 WARN을 찍지 않는다")
    void rejectionWarningIsBatched() throws Exception {
        // 부하 중 거절마다 WARN을 찍으면 로그(json-file 10m×3)가 넘쳐 그 앞의 원인 로그가 밀려납니다.
        Logger logger = (Logger) LoggerFactory.getLogger(ComputedCache.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            when(repository.latestCollectedAt()).thenReturn(t0);
            ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofMillis(50), 32);
            CountDownLatch entered = new CountDownLatch(1);
            Future<Object> holder = holdSlot(pool, cache, "a", entered, release);
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

            for (String key : List.of("b", "c", "d")) {
                assertThatThrownBy(() -> cache.get(key, () -> key)).isInstanceOf(ComputeBusyException.class);
            }
            release.countDown();
            holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS);

            assertThat(cache.rejectedCount()).isEqualTo(3);
            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN))
                    .as("거절 3건에 경고는 첫 거절의 한 줄뿐")
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains("1건을 429로", "누적 1건", "상한 1개", "60초에 한 번"));
        } finally {
            release.countDown();
            logger.detachAppender(appender);
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("QA-131 캐시 적중은 자리를 잡지 않는다 — 계산 자리가 모두 차 있어도 이미 계산한 값은 그대로 돌려준다")
    void hitsNeverWaitForASlot() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofMillis(50), 32);
        Object cached = cache.get("k", Object::new);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdSlot(pool, cache, "x", entered, release);
        assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 자리를 기다렸다면 50ms 뒤 ComputeBusyException이 납니다.
        Object hit = cache.get("k", () -> {
            throw new AssertionError("적중인데 다시 계산함");
        });
        assertThat(hit).isSameAs(cached);
        assertThat(cache.rejectedCount()).isZero();

        release.countDown();
        holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    @DisplayName("QA-131 대기열이 가득 차면 기다리지 않고 바로 거절한다 — 계산 경로가 쥐는 요청 스레드 수를 묶는다")
    void fullQueueRejectsImmediately() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdSlot(pool, cache, "a", entered, release);
        assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<Object> waiter = pool.submit(() -> cache.get("b", () -> (Object) "b"));
        awaitWaiting(cache, 1);

        // 대기(60초)에 들어갔다면 10초 안에 끝나지 않습니다. 바로 거절하면 수 ms입니다.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> cache.get("c", () -> "c")).isInstanceOf(ComputeBusyException.class));

        release.countDown();
        assertThat(holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS)).isEqualTo("a");
        assertThat(waiter.get(GENEROUS_SECONDS, TimeUnit.SECONDS)).as("기다리던 요청은 자리를 받음").isEqualTo("b");
        assertThat(cache.rejectedCount()).isEqualTo(1);
        pool.shutdown();
    }

    @Test
    @DisplayName("QA-131 같은 키를 기다리던 요청은 자리를 얻은 뒤 그사이 채워진 값을 쓴다 — 다시 계산하지 않는다")
    void waiterReusesValueComputedWhileWaiting() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger computed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> first = pool.submit(() -> cache.get("k", () -> {
            computed.incrementAndGet();
            entered.countDown();
            awaitQuietly(release);
            return new Object();
        }));
        assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<Object> second = pool.submit(() -> cache.get("k", () -> {
            computed.incrementAndGet();
            return new Object();
        }));
        awaitWaiting(cache, 1);

        release.countDown();
        Object firstValue = first.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
        assertThat(second.get(GENEROUS_SECONDS, TimeUnit.SECONDS)).isSameAs(firstValue);
        assertThat(computed.get()).isEqualTo(1);
        pool.shutdown();
    }

    @Test
    @DisplayName("QA-131 저장 시각은 자리를 얻은 뒤로 잡는다 — 기다린 만큼 항목 수명이 줄지 않는다")
    void storedAtIsWhenTheSlotWasTaken() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        MovableClock clock = new MovableClock(t0);
        ComputedCache cache = new ComputedCache(repository, clock, 1, Duration.ofSeconds(60), 32);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger computed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdSlot(pool, cache, "x", entered, release);
        assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<Object> waiter = pool.submit(() -> cache.get("k", () -> {
            computed.incrementAndGet();
            return (Object) "k";
        }));
        awaitWaiting(cache, 1);

        clock.set(t0.plusSeconds(50)); // 자리를 50초 기다린 셈
        release.countDown();
        holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
        waiter.get(GENEROUS_SECONDS, TimeUnit.SECONDS);

        clock.set(t0.plusSeconds(50 + ComputedCache.MAX_AGE_SECONDS - 1));
        cache.get("k", () -> {
            computed.incrementAndGet();
            return "k";
        });
        assertThat(computed.get()).as("자리를 얻은 때부터 60초 안이라 적중").isEqualTo(1);
        pool.shutdown();
    }

    @Test
    @DisplayName("QA-131 버전이 없는 경로(저장본 0개·DB 장애)는 상한 밖 — 자리가 다 차도 그대로 계산해 DB 오류(503)를 가리지 않는다")
    void versionlessComputeIsNotLimited() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0, (Instant) null)
                .thenThrow(new IllegalStateException("버전 조회 실패"));
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofMillis(50), 32);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdSlot(pool, cache, "x", entered, release);
        assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 저장본 0개: 자리를 기다렸다면 50ms 뒤 ComputeBusyException
        assertThat(cache.<String>get("y", () -> "y")).isEqualTo("y");
        // 버전 조회 실패(DB 장애): 계산이 낸 DB 오류가 그대로 올라가야 처리기가 503 'DB 연결 불가'로 답합니다.
        RuntimeException databaseDown = new IllegalStateException("DB 연결 불가");
        assertThatThrownBy(() -> cache.get("z", () -> {
            throw databaseDown;
        })).isSameAs(databaseDown);
        assertThat(cache.rejectedCount()).isZero();

        release.countDown();
        holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    @DisplayName("QA-131 계산이 예외를 던져도 자리는 돌려준다")
    void slotIsReleasedWhenComputeThrows() {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofMillis(50), 32);

        assertThatThrownBy(() -> cache.get("a", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        // 자리를 돌려주지 않았다면 50ms 뒤 ComputeBusyException
        assertThat(cache.<String>get("b", () -> "b")).isEqualTo("b");
        assertThat(cache.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("QA-131 상한 설정 0은 자동(코어 수의 절반, 최소 1), 양수는 그 값")
    void zeroMeansAuto() {
        int auto = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        assertThat(new ComputedCache(repository, 0).maxConcurrent()).isEqualTo(auto);
        assertThat(new ComputedCache(repository, 3).maxConcurrent()).isEqualTo(3);
        assertThat(new ComputedCache(repository).maxConcurrent()).as("설정 없이 만든 캐시도 자동").isEqualTo(auto);
    }

    @Test
    @DisplayName("QA-131 상한 설정이 음수면 기동하지 않는다 — 조용히 모든 계산을 거절하지 않게")
    void negativeMaxConcurrentFailsStartup() {
        assertThatThrownBy(() -> new ComputedCache(repository, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPUTE_MAX_CONCURRENT");
    }
}
