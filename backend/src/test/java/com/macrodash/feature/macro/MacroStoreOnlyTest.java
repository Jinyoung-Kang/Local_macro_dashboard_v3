package com.macrodash.feature.macro;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.read.StoreReader;
import org.junit.jupiter.api.Disabled;
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

/** 공개 데모의 읽기 모드 store_only — 화면 조회가 외부 호출을 일으키지 않는다(ADR 0012). */
class MacroStoreOnlyTest {

    @Test
    @Disabled("QA-022 재현 — 수정 전에는 store_only에서도 개별 지표 차트가 수집기 → yfinance를 매번 부른다")
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
}
