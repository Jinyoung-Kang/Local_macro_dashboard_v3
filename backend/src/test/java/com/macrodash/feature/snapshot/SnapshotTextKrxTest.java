package com.macrodash.feature.snapshot;

import com.macrodash.feature.institution.Sec13FService;
import com.macrodash.feature.macro.LiquidityService;
import com.macrodash.feature.macro.MacroService;
import com.macrodash.feature.macro.SectorService;
import com.macrodash.feature.positioning.CotService;
import com.macrodash.feature.positioning.KrFlowsService;
import com.macrodash.feature.positioning.KrxService;
import com.macrodash.feature.positioning.RadarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 원본 텍스트의 투자주체별 선물 수급 구간. 레이더 구간은 이미 "데이터 없음"을 쓰는데
 * 이 구간만 {@code path().asDouble(0)}으로 남아 모르는 값이 "당일 0 · 5일 0"으로 나갔습니다.
 */
class SnapshotTextKrxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("투자주체별 수급을 모르면 0계약이 아니라 '데이터 없음'으로 적는다")
    void unknownContractsAreNotWrittenAsZero() throws Exception {
        KrxService krx = mock(KrxService.class);
        when(krx.investorTrend()).thenReturn(Map.of(
                "available", true, "unit", "계약", "dataDate", "2026-09-30",
                "rows", MAPPER.readTree("""
                        [{"investor":"외국인","netToday":null,"net5d":null,"net20d":null,"stance":null},
                         {"investor":"기관계","netToday":1200,"net5d":-300,"net20d":4500,"stance":"🟢 매수 우위(Long)"}]
                        """)));

        String text = new SnapshotTextService(
                mock(MacroService.class), mock(LiquidityService.class), mock(SectorService.class),
                mock(CotService.class), krx, mock(RadarService.class),
                mock(Sec13FService.class), mock(KrFlowsService.class)).fullText();

        assertThat(text).contains("- 외국인: 당일 데이터 없음 · 5일 데이터 없음 · 20일 데이터 없음 (N/A)");
        assertThat(text).doesNotContain("외국인: 당일 0");
        assertThat(text).contains("- 기관계: 당일 1,200 · 5일 -300 · 20일 4,500 (🟢 매수 우위(Long))");
    }
}
