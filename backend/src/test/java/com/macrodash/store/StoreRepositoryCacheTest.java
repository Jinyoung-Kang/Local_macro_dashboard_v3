package com.macrodash.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 큰 스냅샷은 한 번 파싱해 두고, 다시 읽을 때는 collected_at 한 값만 조회합니다.
 */
class StoreRepositoryCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Snapshot snapshot(Instant collectedAt) {
        return new Snapshot("sec.13f.x.q8", MAPPER.readTree("{\"quarters\":[]}"), "json", "ok", null, collectedAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("같은 collected_at이면 전체를 다시 읽지 않고, 바뀌면 다시 읽는다")
    void reusesParsedSnapshotWhileCollectedAtIsUnchanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant first = Instant.parse("2026-10-02T00:00:00Z");
        Instant later = Instant.parse("2026-10-02T06:00:00Z");
        when(jdbc.query(startsWith("SELECT name, payload"), any(RowMapper.class), any()))
                .thenReturn(List.of(new StoreRepository.Loaded(snapshot(first), 2_000_000L)))
                .thenReturn(List.of(new StoreRepository.Loaded(snapshot(later), 2_000_000L)));
        when(jdbc.query(startsWith("SELECT collected_at"), any(RowMapper.class), any()))
                .thenReturn(List.of(Timestamp.from(first)))     // 1차 재조회: 그대로
                .thenReturn(List.of(Timestamp.from(later)));    // 2차 재조회: 새 수집

        StoreRepository repository = new StoreRepository(jdbc, MAPPER);

        Snapshot a = repository.readSnapshot("sec.13f.x.q8").orElseThrow();
        Snapshot b = repository.readSnapshot("sec.13f.x.q8").orElseThrow();
        assertThat(b).as("바뀌지 않았으면 같은 파싱 결과").isSameAs(a);
        verify(jdbc, times(1)).query(startsWith("SELECT name, payload"), any(RowMapper.class), any());

        Snapshot c = repository.readSnapshot("sec.13f.x.q8").orElseThrow();
        assertThat(c.collectedAt()).isEqualTo(later);
        verify(jdbc, times(2)).query(startsWith("SELECT name, payload"), any(RowMapper.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("작은 스냅샷은 캐시하지 않는다")
    void smallSnapshotsAreNotCached() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(new StoreRepository.Loaded(snapshot(Instant.now()), 500L)));
        StoreRepository repository = new StoreRepository(jdbc, MAPPER);

        repository.readSnapshot("macro.collected");
        repository.readSnapshot("macro.collected");

        verify(jdbc, times(2)).query(startsWith("SELECT name, payload"), any(RowMapper.class), any());
        verify(jdbc, times(0)).query(startsWith("SELECT collected_at"), any(RowMapper.class), any());
    }
}
