package com.macrodash.feature.macro;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.read.StoreReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Disabled;
import tools.jackson.databind.ObjectMapper;

/** 공개 데모의 읽기 모드 store_only — 화면 조회가 외부 호출을 일으키지 않는다(ADR 0012). */
class MacroStoreOnlyTest {

    @Test
    @DisplayName("QA-022 store_only면 개별 지표 차트가 수집기를 부르지 않고 '저장본 없음'으로 답한다")
    void tickerSeriesDoesNotCallCollectorInStoreOnly() {
        StoreReader store = mock(StoreReader.class);
        when(store.readMode()).thenReturn(AppProperties.ReadMode.STORE_ONLY);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        CollectorClient collector = mock(CollectorClient.class);

        Map<String, Object> out = new MacroService(store, collector).tickerSeries("AAPL", "1y");

        verifyNoInteractions(collector);
        assertThat(out).containsEntry("available", false).containsEntry("symbol", "AAPL");
    }

    @Test
    @Disabled("QA-124: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-124 auto 모드에서 수집기가 빈 시계열과 사유를 주면 차트 응답에 그 사유를 싣는다")
    void tickerSeriesCarriesCollectorFailureReason() throws Exception {
        StoreReader store = mock(StoreReader.class);
        when(store.readMode()).thenReturn(AppProperties.ReadMode.AUTO);
        CollectorClient collector = mock(CollectorClient.class);
        when(collector.liveTicker("^GSPC", "1y")).thenReturn(Optional.of(new ObjectMapper().readTree(
                "{\"symbol\": \"^GSPC\", \"points\": [], \"error\": \"yfinance(1.7.0)가 빈 응답을 받았습니다 — Yahoo 차단 의심\"}")));

        Map<String, Object> out = new MacroService(store, collector).tickerSeries("^GSPC", "1y");

        // QA 스택(외부 차단)에서 본 증상: available=false인데 message가 없어 화면은 이유 없이 '표시할 시계열이 없습니다'만 보였습니다.
        assertThat(out).containsEntry("available", false);
        assertThat(String.valueOf(out.get("message"))).contains("Yahoo 차단 의심");
    }
}
