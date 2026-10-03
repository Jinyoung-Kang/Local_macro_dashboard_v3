package com.macrodash.read;

import com.macrodash.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 저장본에서 계산한 응답의 캐시 — 같은 저장본이면 다시 계산하지 않습니다.
 *
 * <p><b>왜</b> — 전체 스냅샷 텍스트·기관 유사도·스코어카드·교집합은 요청마다 수 MB 저장본을 훑어 계산합니다.
 * 저장본은 수집 주기(5분~하루)로만 바뀌는데 화면은 1분마다 다시 묻고, 동시 접속 50명에서 p95가 2~4초였습니다.
 *
 * <p><b>언제 버리는가</b> — 두 조건 중 하나면 다시 계산합니다.
 * <ol>
 *   <li><b>버전</b>: 저장본 전체의 최신 수집 시각({@link StoreRepository#latestCollectedAt()})이 바뀜. 어떤 저장본이든
 *       새로 쓰이면 모든 항목이 무효가 됩니다 — 어느 계산이 어느 저장본에 의존하는지 일일이 적지 않아도
 *       "새 데이터를 옛 계산으로 보여 주는" 일이 없습니다.</li>
 *   <li><b>시간</b>: {@value #MAX_AGE_SECONDS}초. 응답에는 {@code ageSeconds}·{@code stale} 같은 '지금 기준' 값이
 *       들어 있어, 오래 들고 있으면 신선도 표시가 그만큼 틀립니다. 화면의 자동 갱신(최소 60초)과 맞춥니다.</li>
 * </ol>
 * 항목 수는 {@value #MAX_ENTRIES}개까지(가장 오래 안 쓴 것부터 버림) — 스코어카드처럼 파라미터 조합이 많은 경로가
 * 메모리를 끝없이 늘리지 않게 합니다. 계산이 예외를 던지면 아무것도 저장하지 않습니다.
 *
 * <p>돌려주는 객체는 캐시가 공유합니다. <b>받은 쪽에서 바꾸지 마세요.</b> 컨트롤러가 그대로 직렬화하는 용도입니다.
 */
@Component
public class ComputedCache {

    private static final Logger log = LoggerFactory.getLogger(ComputedCache.class);

    public static final long MAX_AGE_SECONDS = 60;
    public static final int MAX_ENTRIES = 256;

    private record Entry(Instant version, Instant storedAt, Object value) {
    }

    private final StoreRepository repository;
    private final Clock clock;
    private final Map<String, Entry> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    @Autowired
    public ComputedCache(StoreRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ComputedCache(StoreRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * {@code key}의 계산 결과. 저장본 버전과 시간이 유효하면 저장된 값을, 아니면 {@code compute}로 새로 계산해
     * 저장한 값을 돌려줍니다. 저장본이 하나도 없거나 버전 조회에 실패하면 캐시를 거치지 않고 계산만 합니다.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> compute) {
        Instant version = currentVersion();
        if (version == null) {
            return compute.get();
        }
        Instant now = clock.instant();
        Entry hit = entries.get(key);
        if (hit != null && version.equals(hit.version())
                && Duration.between(hit.storedAt(), now).getSeconds() < MAX_AGE_SECONDS) {
            return (T) hit.value();
        }
        T value = compute.get();
        entries.put(key, new Entry(version, now, value));
        return value;
    }

    /** 저장된 항목 수(관측·테스트용). */
    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private Instant currentVersion() {
        try {
            return repository.latestCollectedAt();
        } catch (RuntimeException e) {
            log.debug("저장본 버전 조회 실패 — 캐시 없이 계산합니다: {}", e.getMessage());
            return null;
        }
    }
}
