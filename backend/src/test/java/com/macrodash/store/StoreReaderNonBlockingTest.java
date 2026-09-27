package com.macrodash.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "화면은 수집을 기다리지 않는다"(README §4-6)를 코드로 고정합니다.
 *
 * <p>예전에는 저장본이 조금 오래되기만 해도 수집이 끝날 때까지 붙잡고
 * 있었습니다. 실제 로그에서 페이지 한 번 여는 데 이만큼 멈췄습니다.
 *
 * <pre>
 * 07:11:53 sec_13f 시작 → 07:12:25 완료 31.81s   ← 31.8초 대기
 * 07:11:27 fred_series 시작 → 07:11:39 완료 11.56s ← 11.5초 대기
 * </pre>
 */
class StoreReaderNonBlockingTest {

    /** runTask(name, wait) 호출을 기록하는 가짜 수집기. */
    private static class RecordingCollector extends CollectorClient {
        final List<Boolean> waits = new CopyOnWriteArrayList<>();

        RecordingCollector() {
            super(properties());
        }

        @Override
        public Optional<JsonNode> runTask(String taskName, boolean wait) {
            waits.add(wait);
            return Optional.empty();
        }
    }

    private static AppProperties properties() {
        AppProperties props = new AppProperties();
        props.setCollectorUrl("http://localhost:1");
        props.setCollectorTimeoutSeconds(1);
        return props;
    }

    private Snapshot staleSnapshot() {
        return new Snapshot("test.dataset", null, "json", "ok", null,
                Instant.now().minus(Duration.ofDays(3)));
    }

    private StoreReader reader(StoreRepository repository, CollectorClient collector) {
        AppProperties props = properties();
        props.setReadMode("auto");
        return new StoreReader(repository, collector, props);
    }

    @Test
    @DisplayName("저장본이 오래됐어도 수집을 기다리지 않고 바로 돌려준다")
    void staleSnapshotIsReturnedWithoutWaiting() {
        StoreRepository repository = mock(StoreRepository.class);
        Snapshot stale = staleSnapshot();
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(stale));

        RecordingCollector collector = new RecordingCollector();
        Optional<Snapshot> result = reader(repository, collector).read("test.dataset", 60, "some_task");

        assertThat(result).containsSame(stale);
        assertThat(collector.waits)
                .as("보여 줄 값이 있으면 wait=false로 요청해야 합니다")
                .containsExactly(false);
    }

    @Test
    @DisplayName("저장본이 아예 없으면 기다린다 — 빈 화면보다는 낫다")
    void missingSnapshotStillWaits() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("test.dataset", 60, "some_task");

        assertThat(collector.waits)
                .as("보여 줄 값이 없을 때는 기다려야 합니다")
                .containsExactly(true);
    }

    @Test
    @DisplayName("같은 태스크를 짧은 시간에 반복 요청하지 않는다")
    void repeatedTriggersAreThrottled() {
        // 화면 하나가 스냅샷을 병렬로 읽으면 같은 태스크 요청이 그만큼 나갑니다.
        // 실제 로그에서 한 번의 페이지 로드에 fred_series 요청이 7건이었습니다.
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(staleSnapshot()));

        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector);

        for (int i = 0; i < 7; i++) {
            reader.read("fred.series.DGS10", 60, "fred_series");
        }

        assertThat(collector.waits)
                .as("7번 읽어도 수집 요청은 한 번이어야 합니다")
                .hasSize(1);
    }

    @Test
    @DisplayName("다른 태스크는 서로를 막지 않는다")
    void differentTasksAreNotThrottledTogether() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(staleSnapshot()));

        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector);

        reader.read("fred.series.DGS10", 60, "fred_series");
        reader.read("ticker.VIX.5y", 60, "volatility_history");

        assertThat(collector.waits).hasSize(2);
    }

    @Test
    @DisplayName("신선하면 수집을 요청하지 않는다")
    void freshSnapshotTriggersNothing() {
        StoreRepository repository = mock(StoreRepository.class);
        Snapshot fresh = new Snapshot("test.dataset", null, "json", "ok", null, Instant.now());
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(fresh));

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("test.dataset", 3600, "some_task");

        assertThat(collector.waits).isEmpty();
    }

    // =========================================================================
    // 수동 새로고침이 분기 공시까지 다시 받던 문제
    // =========================================================================
    // 실제 로그: 5분 사이에 SEC 전수 수집이 세 번 돌았습니다.
    //   07:20:33 sec_13f 51.80s   ← 스케줄(weekly)
    //   07:21:34 POST /refresh → 07:22:01 sec_13f 31.77s
    //   07:24:33 POST /refresh → 07:24:44 sec_13f 32.05s
    // SEC는 호출 한도를 명시하고 초과하면 차단합니다.

    private StoreRepository repositoryWithRefresh(Snapshot snapshot, Instant refreshedAt) {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(snapshot));
        when(repository.refreshRequestedAt(anyString())).thenReturn(refreshedAt);
        return repository;
    }

    @Test
    @DisplayName("새로고침을 눌러도 방금 받은 분기 공시는 다시 받지 않는다")
    void manualRefreshDoesNotRefetchFreshSlowData() {
        // 3분 전에 받은 13F. 그 뒤에 새로고침을 눌렀습니다.
        Snapshot justCollected = new Snapshot("sec.13f.x.q8", null, "json", "ok", null,
                Instant.now().minus(Duration.ofMinutes(3)));
        StoreRepository repository = repositoryWithRefresh(justCollected, Instant.now());

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("sec.13f.x.q8", Datasets.MAX_AGE_SLOW, "sec_13f");

        assertThat(collector.waits)
                .as("3분 전 받은 분기 공시를 다시 받을 이유가 없습니다")
                .isEmpty();
    }

    @Test
    @DisplayName("충분히 오래된 분기 공시는 새로고침으로 다시 받는다")
    void manualRefreshStillRefetchesOldSlowData() {
        Snapshot old = new Snapshot("sec.13f.x.q8", null, "json", "ok", null,
                Instant.now().minus(Duration.ofHours(7)));
        StoreRepository repository = repositoryWithRefresh(old, Instant.now());

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("sec.13f.x.q8", Datasets.MAX_AGE_SLOW, "sec_13f");

        assertThat(collector.waits).containsExactly(false);
    }

    @Test
    @DisplayName("시세성 데이터는 새로고침 의도를 그대로 존중한다")
    void manualRefreshRefetchesRealtimeData() {
        // 새로고침을 누르는 이유는 대개 이쪽입니다. 2분 전 값이라도 다시 받습니다.
        Snapshot recent = new Snapshot("macro.collected", null, "json", "ok", null,
                Instant.now().minus(Duration.ofMinutes(2)));
        StoreRepository repository = repositoryWithRefresh(recent, Instant.now());

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("macro.collected", Datasets.MAX_AGE_REALTIME,
                "macro_collected");

        assertThat(collector.waits).containsExactly(false);
    }

    @Test
    @DisplayName("데이터셋 종류별 최소 재수집 간격")
    void minRefetchIntervalsByClass() {
        assertThat(Datasets.minRefetchSeconds(Datasets.MAX_AGE_REALTIME)).isEqualTo(60);
        assertThat(Datasets.minRefetchSeconds(Datasets.MAX_AGE_DAILY)).isEqualTo(30 * 60);
        assertThat(Datasets.minRefetchSeconds(Datasets.MAX_AGE_SLOW)).isEqualTo(6 * 60 * 60);
    }

    // =========================================================================
    // 저장본이 없는 데이터셋을 요청할 때마다 전체 수집을 다시 기다리던 문제 (PERF-01)
    // =========================================================================
    // 재현(수집기 지연 5초): 없는 CIK 3개를 넣은 요청 하나가 13F 수집을 6번 기다려 30초.
    // 실제로는 수집기 대기 한도(90초)만큼씩 붙잡고, 그때마다 SEC를 다시 크롤링합니다.

    /** 시각을 테스트가 움직이는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
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

    private StoreReader reader(StoreRepository repository, CollectorClient collector, Clock clock) {
        AppProperties props = properties();
        props.setReadMode("auto");
        return new StoreReader(repository, collector, props, clock);
    }

    @Test
    @DisplayName("저장본이 없어 한 번 기다렸으면, 곧바로 다시 기다리지 않는다")
    void missingSnapshotIsNotAwaitedAgainRightAway() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());
        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector, new MutableClock());

        reader.read("sec.13f.0000000000.q8", Datasets.MAX_AGE_SLOW, "sec_13f");
        Optional<Snapshot> second = reader.read("sec.13f.0000000000.q8", Datasets.MAX_AGE_SLOW, "sec_13f");

        assertThat(second).isEmpty();
        assertThat(collector.waits)
                .as("방금 기다린 수집을 또 기다리면 결과는 같고 시간만 버립니다")
                .containsExactly(true);
    }

    @Test
    @DisplayName("같은 태스크가 만드는 다른 데이터셋도 다시 기다리지 않는다")
    void sameTaskIsNotAwaitedForAnotherMissingDataset() {
        // 없는 CIK 여러 개, 없는 FRED 시계열 여러 개가 한 요청에 섞여 들어오는 경우입니다.
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());
        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector, new MutableClock());

        for (String cik : List.of("1", "2", "3")) {
            reader.read(Datasets.sec13f(cik, 8), Datasets.MAX_AGE_SLOW, "sec_13f");
        }

        assertThat(collector.waits).containsExactly(true);
    }

    @Test
    @DisplayName("다른 태스크는 각자 한 번씩 기다린다")
    void differentMissingTasksEachWaitOnce() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());
        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector, new MutableClock());

        reader.read("fred.series.DGS10", Datasets.MAX_AGE_DAILY, "fred_series");
        reader.read("sec.13f.1.q8", Datasets.MAX_AGE_SLOW, "sec_13f");

        assertThat(collector.waits).containsExactly(true, true);
    }

    @Test
    @DisplayName("시간이 지나면 다시 기다린다 (키를 넣은 뒤 등 상황이 바뀌었을 수 있음)")
    void missingSnapshotIsAwaitedAgainAfterRetryInterval() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());
        RecordingCollector collector = new RecordingCollector();
        MutableClock clock = new MutableClock();
        StoreReader reader = reader(repository, collector, clock);

        reader.read("fred.series.DGS10", Datasets.MAX_AGE_DAILY, "fred_series");
        clock.advance(Duration.ofMillis(StoreReader.MISSING_RETRY_MS + 1));
        reader.read("fred.series.DGS10", Datasets.MAX_AGE_DAILY, "fred_series");

        assertThat(collector.waits).containsExactly(true, true);
    }

    @Test
    @DisplayName("기다리지 않을 때도 저장본은 다시 읽는다 — 그 사이 수집이 끝났을 수 있다")
    void skippedWaitStillRereadsSnapshot() {
        StoreRepository repository = mock(StoreRepository.class);
        Snapshot arrived = new Snapshot("fred.series.DGS2", null, "json", "ok", null, Instant.now());
        when(repository.readSnapshot("fred.series.DGS10")).thenReturn(Optional.empty());
        when(repository.readSnapshot("fred.series.DGS2"))
                .thenReturn(Optional.empty())          // 요청이 처음 읽을 때는 아직 없음
                .thenReturn(Optional.of(arrived));     // 방금 끝난 수집이 만들어 둠
        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector, new MutableClock());

        reader.read("fred.series.DGS10", Datasets.MAX_AGE_DAILY, "fred_series");
        Optional<Snapshot> result = reader.read("fred.series.DGS2", Datasets.MAX_AGE_DAILY, "fred_series");

        assertThat(result).containsSame(arrived);
        assertThat(collector.waits).containsExactly(true);
    }

    @Test
    @DisplayName("같은 태스크를 동시에 기다리는 요청들은 수집 한 번을 함께 기다린다")
    void concurrentMissingReadsShareOneWait() throws Exception {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingCollector collector = new RecordingCollector() {
            @Override
            public Optional<JsonNode> runTask(String taskName, boolean wait) {
                waits.add(wait);
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);    // 수집이 도는 중
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        StoreReader reader = reader(repository, collector, new MutableClock());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Snapshot>> first = pool.submit(
                    () -> reader.read("fred.series.DGS10", Datasets.MAX_AGE_DAILY, "fred_series"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Optional<Snapshot>> second = pool.submit(
                    () -> reader.read("fred.series.DGS2", Datasets.MAX_AGE_DAILY, "fred_series"));
            Thread.sleep(100);                              // 두 번째가 합류할 시간
            assertThat(second.isDone())
                    .as("진행 중인 수집이 끝나기 전에 '없음'으로 먼저 답하면 첫 화면이 비어 보입니다")
                    .isFalse();
            release.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(collector.waits).containsExactly(true);
    }
}
