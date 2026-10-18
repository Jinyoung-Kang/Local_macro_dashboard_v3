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
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    /**
     * 새 스레드에서 {@code cache.get(key, compute)}를 부릅니다. 돌려준 값이나 던진 예외는 {@code result}에 담기고, 던진
     * 예외는 {@code thrown}에도 그대로 남깁니다({@code result.get}은 CompletionException을 벗겨 원인을 주므로).
     */
    private static Caller call(ComputedCache cache, String key, Supplier<Object> compute) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                Object value = cache.get(key, compute);
                interruptedAfter.set(Thread.currentThread().isInterrupted());
                result.complete(value);
            } catch (Throwable e) {
                interruptedAfter.set(Thread.currentThread().isInterrupted());
                thrown.set(e);
                result.completeExceptionally(e);
            }
        }, "cache-caller-" + key);
        thread.setDaemon(true); // 실패한 테스트가 래치를 못 열어도 JVM 종료를 막지 않게
        thread.start();
        return new Caller(thread, result, thrown, interruptedAfter);
    }

    /**
     * @param thrown           호출이 던진 예외 그대로(던지지 않았으면 null)
     * @param interruptedAfter 호출이 끝난 뒤 그 스레드의 interrupt 표시가 남아 있었는지
     */
    private record Caller(Thread thread, CompletableFuture<Object> result, AtomicReference<Throwable> thrown,
                          AtomicBoolean interruptedAfter) {

        /**
         * 호출이 끝났거나 캐시 안에서 멈춰(자리나 앞선 계산을 기다림) 있을 때까지 기다립니다. 어느 쪽에서 기다리는지는
         * 묻지 않아, 고치기 전(자리 대기열)과 고친 뒤(앞선 계산)를 같은 방법으로 셉니다.
         */
        Caller awaitParkedOrDone() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS);
            while (!result.isDone() && !parkedInCache()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("호출(" + thread.getName() + ")이 캐시 안에서 멈추지도 끝나지도 않았습니다: "
                            + thread.getState());
                }
                Thread.sleep(1);
            }
            return this;
        }

        private boolean parkedInCache() {
            Thread.State state = thread.getState();
            if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                return false;
            }
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(ComputedCache.class.getName()) && frame.getMethodName().equals("get")) {
                    return true;
                }
            }
            return false;
        }

        Object value() throws Exception {
            return result.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
        }

        /** 호출이 던진 예외 — 벗기지 않은 그대로. 값을 돌려줬으면 실패합니다. */
        Throwable failure() throws Exception {
            try {
                Object value = result.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("예외 대신 값을 돌려줬습니다: " + value);
            } catch (ExecutionException e) {
                return thrown.get();
            }
        }
    }

    /** 테스트가 시각을 옮길 수 있는 시계. 다음 몇 번의 시각을 차례로 정해 둘 수도 있습니다({@link #next}). */
    private static final class MovableClock extends Clock {
        private volatile Instant now;
        private final Queue<Instant> upcoming = new ConcurrentLinkedQueue<>();

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        /** 다음 호출들에 이 시각을 차례로 돌려주고, 다 쓰면 {@link #set}한 시각으로 돌아갑니다. */
        void next(Instant... instants) {
            upcoming.addAll(List.of(instants));
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
            Instant queued = upcoming.poll();
            return queued != null ? queued : now;
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
    @DisplayName("QA-131 같은 키를 놓친 요청은 앞선 계산이 자리를 기다리는 동안에도 대기열 자리를 잡지 않고, 그 계산이 끝나면 같은 값을 받는다")
    void followerDoesNotQueueWhileLeaderWaitsForSlot() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicInteger computed = new AtomicInteger();
        Supplier<Object> compute = () -> {
            computed.incrementAndGet();
            return new Object();
        };
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> holder = holdSlot(pool, cache, "x", entered, release);
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            Caller leader = call(cache, "k", compute);
            awaitWaiting(cache, 1); // 앞선 계산이 자리를 기다림
            Caller follower = call(cache, "k", compute).awaitParkedOrDone();
            assertThat(cache.waiting()).as("자리를 기다리는 것은 앞선 계산 하나뿐").isEqualTo(1);
            assertThat(cache.inflight()).as("자리를 쥔 x와 자리를 기다리는 k").isEqualTo(2);

            release.countDown();
            holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
            Object value = leader.value();
            assertThat(follower.value()).isSameAs(value);
            assertThat(computed.get()).isEqualTo(1);
            assertThat(cache.inflight()).isZero();
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("QA-131 같은 키를 동시에 놓친 요청은 계산 하나를 함께 기다려 같은 값을 받는다 — 대기열을 채워 429가 되지 않는다")
    void sameKeyMissesShareOneComputation() throws Exception {
        // QA 스택 측정: 백엔드 재기동 직후 5명이 무거운 화면을 한꺼번에 열자(요청 45건, 서로 다른 키 9개) 14–37건이
        // 429였습니다. 같은 키를 놓친 요청도 각자 자리 대기열에 서서, 계산은 9번이면 되는데 대기열 상한(32건)·2초 대기를 넘깁니다.
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(2), 2);
        int callers = 20;
        AtomicInteger computed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<Object> heavy = () -> {
            computed.incrementAndGet();
            entered.countDown();
            awaitQuietly(release);
            return new Object();
        };
        List<Caller> calls = new ArrayList<>();
        try {
            calls.add(call(cache, "k", heavy));
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            // 계산이 도는 동안 같은 키 요청이 하나씩 도착 — 앞 요청이 캐시 안에서 멈춘 뒤에 다음을 보내 순서를 고정합니다.
            for (int i = 1; i < callers; i++) {
                calls.add(call(cache, "k", heavy).awaitParkedOrDone());
            }
            assertThat(cache.waiting()).as("따르는 요청은 자리 대기열에 서지 않음").isZero();
            assertThat(cache.inflight()).as("계산 중인 키+버전").isEqualTo(1);
        } finally {
            release.countDown();
        }

        List<Object> values = new ArrayList<>();
        int busy = 0;
        for (Caller caller : calls) {
            try {
                values.add(caller.value());
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof ComputeBusyException)) {
                    throw e;
                }
                busy++;
            }
        }
        assertThat(busy).as("429로 거절된 같은 키 요청 수").isZero();
        assertThat(computed.get()).as("계산 횟수").isEqualTo(1);
        assertThat(values).hasSize(callers).allSatisfy(value -> assertThat(value).isSameAs(values.get(0)));
        assertThat(cache.rejectedCount()).isZero();
        assertThat(cache.inflight()).as("계산을 맡은 요청이 끝나면 비움").isZero();
    }

    @Test
    @DisplayName("QA-131 앞선 계산을 따르는 요청 수에도 상한이 있다 — 넘친 같은 키 요청은 기다리지 않고 바로 429")
    void followersAreBounded() throws Exception {
        // 리뷰 탐침: 따르는 요청은 자리도 대기열 자리도 잡지 않아, 자리 1·대기열 32에서 캐시에 없는 키 33개에 6건씩(198건)
        // 겹쳐 보내자 198개가 캐시 안에서 멈추고 바로 429는 0건이었습니다(같은 키를 한 번만 계산하기 전에는 33개만 멈추고
        // 165건은 바로 429). Tomcat 요청 스레드는 기본 200개라 health·로그인·캐시 적중까지 밀립니다.
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        int bound = ComputedCache.MAX_FOLLOWERS;
        int extra = 3;
        AtomicInteger computed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Caller> followers = new ArrayList<>();
        Caller leader;
        try {
            leader = call(cache, "k", () -> {
                computed.incrementAndGet();
                entered.countDown();
                awaitQuietly(release);
                return new Object();
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            // 하나씩 보내 앞 요청이 캐시 안에서 멈추거나 끝난 뒤 다음을 보냅니다 — 상한까지 멈추고 그 뒤는 바로 끝나야 합니다.
            for (int i = 0; i < bound + extra; i++) {
                followers.add(call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone());
            }
            assertThat(followers.subList(0, bound)).as("상한까지는 앞선 계산을 기다림")
                    .allSatisfy(follower -> assertThat(follower.result()).isNotDone());
            assertThat(cache.followers()).isEqualTo(bound);
            for (Caller turnedAway : followers.subList(bound, bound + extra)) {
                assertThat(turnedAway.result()).as("상한을 넘은 요청은 기다리지 않고 바로 끝남").isDone();
                assertThat(turnedAway.failure()).isInstanceOf(ComputeBusyException.class);
            }
        } finally {
            release.countDown();
        }

        Object value = leader.value();
        for (Caller follower : followers.subList(0, bound)) {
            assertThat(follower.value()).isSameAs(value);
        }
        assertThat(computed.get()).isEqualTo(1);
        assertThat(cache.rejectedCount()).isEqualTo(extra);
        assertThat(cache.inflight()).isZero();
        assertThat(cache.followers()).as("거절된 요청과 다 기다린 요청 모두 셈에서 빠짐").isZero();
    }

    /** 불리면 안 되는 계산 — 앞선 계산을 따라야 할 요청이나 자리를 얻지 못한 요청이 계산하면 드러납니다. */
    private static final Supplier<Object> MUST_NOT_COMPUTE = () -> {
        throw new AssertionError("불리면 안 되는 계산이 불림");
    };

    @Test
    @DisplayName("QA-131 앞선 계산이 예외를 던지면 따르는 요청도 같은 예외를 받고, 저장하지 않았으니 다음 요청은 다시 계산한다")
    void followersShareLeadersException() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        RuntimeException failure = new IllegalArgumentException("계산 실패"); // 계산이 낸 예외는 무엇이든(400·500) 그대로
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Caller> followers = new ArrayList<>();
        Caller leader;
        try {
            leader = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                throw failure;
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) {
                followers.add(call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone());
            }
        } finally {
            release.countDown();
        }

        assertThat(leader.failure()).isSameAs(failure);
        for (Caller follower : followers) {
            assertThat(follower.failure()).isSameAs(failure);
        }
        assertThat(cache.inflight()).isZero();
        assertThat(cache.size()).isZero();
        assertThat(cache.<String>get("k", () -> "다시 계산")).isEqualTo("다시 계산");
        assertThat(cache.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("QA-131 앞선 계산이 Error를 던져도 따르는 요청은 감싸지 않은 같은 Error를 곧바로 받는다 — 대기 시간을 헛기다리다 429가 되지 않는다")
    void followersShareLeadersError() throws Exception {
        // 수 MB 저장본을 훑는 계산은 부하 중 OutOfMemoryError를 낼 수 있습니다. 앞선 계산이 Error를 넘기지 않으면 따르는
        // 요청은 결과 없이 따르기 대기(여기서는 10초)를 다 채운 뒤 429를 받습니다.
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32,
                Duration.ofSeconds(10));
        Error failure = new OutOfMemoryError("테스트: 계산 중 메모리 부족");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Caller> followers = new ArrayList<>();
        Caller leader;
        try {
            leader = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                throw failure;
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) {
                followers.add(call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone());
            }
        } finally {
            release.countDown();
        }

        assertThat(leader.failure()).isSameAs(failure);
        for (Caller follower : followers) {
            assertThat(follower.failure()).isSameAs(failure);
        }
        assertThat(cache.rejectedCount()).isZero();
        assertThat(cache.inflight()).isZero();
        assertThat(cache.<String>get("k", () -> "다시 계산")).as("자리도 돌려줌 — 아니면 60초 기다림").isEqualTo("다시 계산");
    }

    @Test
    @DisplayName("QA-131 앞선 계산이 CompletionException을 던져도 따르는 요청은 벗기지 않은 같은 예외를 받는다")
    void followersShareLeadersCompletionException() throws Exception {
        // 앞선 계산이 결과를 future의 예외로 넘기면 CompletableFuture.get이 CompletionException을 벗겨 원인을 줍니다.
        // 예를 들어 CompletionException(InvalidRequestException)이면 앞선 요청은 500, 따르는 요청은 400이 됩니다.
        // 지금 계산 경로에서 CompletionException을 던지는 곳은 없지만, javadoc은 '같은 예외를 받는다'고 약속합니다.
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        RuntimeException failure = new CompletionException(new IllegalArgumentException("원인"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller leader;
        Caller follower;
        try {
            leader = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                throw failure;
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            follower = call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone();
        } finally {
            release.countDown();
        }

        assertThat(leader.failure()).isSameAs(failure);
        assertThat(follower.failure()).isSameAs(failure);
        assertThat(cache.inflight()).isZero();
    }

    @Test
    @DisplayName("QA-131 앞선 계산이 자리를 얻지 못해 429면 따르는 요청도 429 — 거절은 돌려보낸 요청마다 세고, 얻지 못한 자리는 돌려주지 않는다")
    void followersShareLeadersRejection() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch enteredAgain = new CountDownLatch(1);
        CountDownLatch releaseAgain = new CountDownLatch(1);
        try {
            Future<Object> holder = holdSlot(pool, cache, "x", entered, release);
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            Caller leader = call(cache, "k", MUST_NOT_COMPUTE);
            awaitWaiting(cache, 1);
            List<Caller> followers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                followers.add(call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone());
            }
            // 2초 대기가 끝나기를 시간으로 맞추지 않으려고, 자리를 기다리는 앞선 계산을 interrupt해 같은 거절 경로로 보냅니다.
            leader.thread().interrupt();

            Throwable busy = leader.failure();
            assertThat(busy).isInstanceOf(ComputeBusyException.class);
            for (Caller follower : followers) {
                assertThat(follower.failure()).isSameAs(busy);
            }
            assertThat(cache.rejectedCount()).as("앞선 계산 1건 + 따르는 요청 3건").isEqualTo(4);
            assertThat(cache.inflight()).as("자리를 쥔 x만 남음").isEqualTo(1);
            release.countDown();
            holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS);

            // 자리를 얻지 못한 앞선 계산이 자리를 '돌려줬다면' 상한이 2가 되어, 아래 z는 기다리지 않고 바로 계산합니다.
            Future<Object> holderAgain = holdSlot(pool, cache, "y", enteredAgain, releaseAgain);
            assertThat(enteredAgain.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            Caller next = call(cache, "z", () -> "z").awaitParkedOrDone();
            assertThat(next.result()).as("자리가 하나뿐이라 z는 기다림").isNotDone();
            assertThat(cache.waiting()).isEqualTo(1);
            releaseAgain.countDown();
            holderAgain.get(GENEROUS_SECONDS, TimeUnit.SECONDS);
            assertThat(next.value()).isEqualTo("z");
        } finally {
            release.countDown();
            releaseAgain.countDown();
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("QA-131 앞선 계산이 대기 시간 안에 끝나지 않으면 따르는 요청은 429로 거절로 세고, 앞선 계산은 끝까지 돌아 저장한다")
    void followerTimesOutWith429() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32,
                Duration.ofMillis(100));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller leader;
        try {
            leader = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                return "k";
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> cache.get("k", MUST_NOT_COMPUTE))
                    .isInstanceOfSatisfying(ComputeBusyException.class,
                            busy -> assertThat(busy.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
            assertThat(cache.rejectedCount()).isEqualTo(1);
            assertThat(cache.inflight()).as("앞선 계산은 아직 도는 중").isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(leader.value()).isEqualTo("k");
        assertThat(cache.inflight()).isZero();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA-131 앞선 계산을 기다리다 interrupt되면 429로 끝내고 interrupt 표시는 남긴다")
    void interruptedFollowerEndsIn429() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller leader;
        Caller follower;
        try {
            leader = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                return "k";
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            follower = call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone();
            follower.thread().interrupt();

            assertThat(follower.failure()).isInstanceOf(ComputeBusyException.class);
            assertThat(follower.interruptedAfter()).as("interrupt 표시를 되살림").isTrue();
            assertThat(cache.rejectedCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(leader.value()).isEqualTo("k");
        assertThat(cache.inflight()).isZero();
    }

    @Test
    @DisplayName("QA-131 새 저장본 버전을 본 요청은 옛 버전 계산을 따르지 않고 새로 계산한다")
    void newerVersionDoesNotFollowOlderComputation() throws Exception {
        Instant t1 = t0.plusSeconds(300);
        when(repository.latestCollectedAt()).thenReturn(t0, t1);
        // 자리 2개: 새 버전 계산이 자리를 기다리지 않게. 따르기 대기는 짧게 둬, 잘못 따르면 바로 429로 드러나게 합니다.
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 2, Duration.ofSeconds(60), 32,
                Duration.ofMillis(100));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller older;
        try {
            older = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                return "옛 버전";
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(cache.<String>get("k", () -> "새 버전")).isEqualTo("새 버전");
            assertThat(cache.inflight()).as("옛 버전 계산은 아직 도는 중").isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(older.value()).isEqualTo("옛 버전");
        assertThat(cache.inflight()).isZero();
        assertThat(cache.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("QA-131 계산 안에서 같은 키를 다시 부르면 자기 계산을 따르다 대기 시간 뒤 429로 끝난다 — 영원히 멈추지 않는다")
    void reentrantSameKeyEndsIn429() {
        // 서비스(계산 안)에서 이 캐시를 부르면 안 되지만(javadoc), 그래도 요청 스레드를 영원히 묶지 않는지 봅니다.
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofMillis(50), 32,
                Duration.ofMillis(100));

        assertTimeoutPreemptively(Duration.ofSeconds(GENEROUS_SECONDS), () ->
                assertThatThrownBy(() -> cache.get("k", () -> cache.get("k", () -> "안쪽")))
                        .isInstanceOf(ComputeBusyException.class));
        assertThat(cache.inflight()).isZero();
        assertThat(cache.rejectedCount()).as("안쪽 호출의 대기 초과 1건").isEqualTo(1);
        assertThat(cache.<String>get("k", () -> "다시")).as("자리도 돌려줌 — 아니면 50ms 뒤 429").isEqualTo("다시");
    }

    @Test
    @DisplayName("QA-131 캐시 적중은 계산 중인 키가 있어도 기다리지 않는다 — 자리도 앞선 계산도 거치지 않는다")
    void hitsNeverWaitForInflightComputation() throws Exception {
        when(repository.latestCollectedAt()).thenReturn(t0);
        ComputedCache cache = new ComputedCache(repository, Clock.fixed(t0, ZoneOffset.UTC), 1, Duration.ofSeconds(60), 32);
        Object cached = cache.get("h", Object::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller leader;
        Caller follower;
        try {
            leader = call(cache, "k", () -> {
                entered.countDown();
                awaitQuietly(release);
                return "k";
            });
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();
            follower = call(cache, "k", MUST_NOT_COMPUTE).awaitParkedOrDone();

            // 자리(60초)나 앞선 계산(30초)을 기다렸다면 시간 안에 끝나지 않습니다. 적중이면 수 ms입니다.
            Object hit = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> cache.get("h", MUST_NOT_COMPUTE));
            assertThat(hit).isSameAs(cached);
            assertThat(cache.inflight()).isEqualTo(1);
            assertThat(cache.rejectedCount()).isZero();
        } finally {
            release.countDown();
        }
        assertThat(follower.value()).isSameAs(leader.value());
    }

    @Test
    @DisplayName("QA-131 놓친 뒤 앞선 계산이 막 끝나 값이 채워졌으면 자리를 잡지 않고 그 값을 쓴다 — 자리가 다 차 있어도 429가 아니다")
    void valueFilledAfterMissIsUsedWithoutSlot() throws Exception {
        // 첫 확인에서 놓친 뒤 계산을 맡기 전에, 같은 키·같은 버전을 계산하던 앞선 요청이 끝나 저장하는 틈이 있습니다.
        // 그 틈을 시각을 차례로 돌려주는 시계로 만듭니다: 첫 확인은 항목이 만료된 시각, 맡은 뒤 다시 볼 때는 살아 있는 시각.
        when(repository.latestCollectedAt()).thenReturn(t0);
        MovableClock clock = new MovableClock(t0);
        ComputedCache cache = new ComputedCache(repository, clock, 1, Duration.ofMillis(50), 32);
        Object stored = cache.get("k", Object::new);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> holder = holdSlot(pool, cache, "x", entered, release);
            assertThat(entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS)).isTrue();

            clock.next(t0.plusSeconds(ComputedCache.MAX_AGE_SECONDS + 1), t0.plusSeconds(1));
            // 자리를 기다렸다면 50ms 뒤 ComputeBusyException, 다시 계산했다면 AssertionError입니다.
            assertThat(cache.get("k", MUST_NOT_COMPUTE)).isSameAs(stored);
            assertThat(cache.rejectedCount()).isZero();
            assertThat(cache.waiting()).isZero();
            assertThat(cache.inflight()).as("자리를 쥔 x만 남음").isEqualTo(1);

            release.countDown();
            assertThat(holder.get(GENEROUS_SECONDS, TimeUnit.SECONDS)).isEqualTo("x");
        } finally {
            release.countDown();
            pool.shutdown();
        }
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
