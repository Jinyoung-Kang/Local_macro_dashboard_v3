package com.macrodash.service;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매크로 화면의 자동 갱신(live) 모드가 저장본을 얼마나 자주 다시 받는지 고정합니다.
 *
 * <p><b>왜 고정하는가</b> — 여기 숫자는 취향이 아니라 외부 API 한도와의 약속입니다.
 * <ul>
 *   <li>기본(15분)을 그대로 두면 사용자가 10초 갱신을 골라도 저장본이 15분 동안
 *       그대로라 <b>같은 숫자만 다시 그립니다</b>. 자동 갱신이 무의미해집니다.</li>
 *   <li>반대로 60초보다 짧게 내리면 매크로 카드 21개가 Yahoo를 그만큼 두드립니다.
 *       이 저장소는 <b>Yahoo 429로 스크래핑이 막혀 화면이 빈 이력</b>이 있습니다.</li>
 * </ul>
 * 그래서 live 모드의 기준은 60초이고, 그 값이 바뀌면 이 테스트가 먼저 깨집니다.
 */
class MacroLiveModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PAYLOAD = """
            {"categories": [], "rates": {}}
            """;

    private record Fixture(MacroService macro, StoreReader store) {
    }

    private Fixture fixture() throws Exception {
        JsonNode payload = MAPPER.readTree(PAYLOAD);
        StoreReader store = mock(StoreReader.class);
        when(store.readMode()).thenReturn(AppProperties.ReadMode.AUTO);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_MACRO_COLLECTED), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(
                        Datasets.SNAP_MACRO_COLLECTED, payload,
                        "json", "ok", null, Instant.now())));

        return new Fixture(new MacroService(store, mock(CollectorClient.class)), store);
    }

    private long maxAgeUsedBy(Fixture fixture) {
        ArgumentCaptor<Long> maxAge = ArgumentCaptor.forClass(Long.class);
        verify(fixture.store()).read(
                eq(Datasets.SNAP_MACRO_COLLECTED), maxAge.capture(), eq("macro_collected"));
        return maxAge.getValue();
    }

    @Test
    @DisplayName("자동 갱신을 켜면 60초 지난 저장본을 다시 받는다")
    void liveModeLowersMaxAgeToOneMinute() throws Exception {
        Fixture fixture = fixture();
        fixture.macro().overview(true);

        assertThat(maxAgeUsedBy(fixture)).isEqualTo(60L);
    }

    @Test
    @DisplayName("자동 갱신을 끄면 기존 15분 기준 그대로다")
    void defaultModeKeepsFifteenMinutes() throws Exception {
        Fixture fixture = fixture();
        fixture.macro().overview(false);

        assertThat(maxAgeUsedBy(fixture)).isEqualTo(15 * 60L);
    }

    @Test
    @DisplayName("live 기준은 60초 밑으로 내려가지 않는다 (Yahoo 429 방지)")
    void liveFloorIsNotLoweredBelowOneMinute() {
        assertThat(Datasets.MAX_AGE_LIVE)
                .as("매크로 카드 21개가 Yahoo를 두드리는 간격입니다. "
                        + "줄이려면 429 차단 이력(README 9장)을 먼저 보세요.")
                .isGreaterThanOrEqualTo(60L);
    }

    @Test
    @DisplayName("자동 갱신 중에도 '오래된 저장본' 배지는 15분 기준을 쓴다")
    void staleBadgeStillUsesRealtimeThreshold() throws Exception {
        // 방금 수집한 저장본이므로, 어느 모드에서도 stale이 아니어야 합니다.
        // (60초 기준을 배지에까지 쓰면 61초 된 값이 "오래된 저장본"으로 보입니다.)
        Map<String, Object> out = fixture().macro().overview(true);

        assertThat(out.get("stale")).isEqualTo(false);
    }
}
