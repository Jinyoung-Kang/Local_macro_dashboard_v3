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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 원본 텍스트(AI 입력·복사용)의 수급 레이더 구간.
 *
 * <p>값을 모르는 칸은 "데이터 없음"이어야 합니다. {@code JsonNode.path(..).asDouble()}은 값이
 * 없으면 0.0을 돌려줘서, 토스 대체 경로처럼 가격을 몰라 금액이 비어 있는 종목이
 * "0.00억원"으로 AI에 전달됐습니다(다른 구간은 모두 "데이터 없음"으로 적습니다).
 */
class SnapshotTextRadarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("금액·가격·등락률을 모르는 종목은 0이 아니라 '데이터 없음'으로 적는다")
    void unknownValuesAreNotWrittenAsZero() throws Exception {
        RadarService radar = mock(RadarService.class);
        when(radar.ranking(eq("KOSPI"), any(), any(), anyInt(), any(), any())).thenReturn(Map.of(
                "available", true,
                "source", "토스증권 공식 (추정)",
                "rows", MAPPER.readTree("""
                        [{"name": "가격모름", "code": "000001",
                          "netAmountEok": null, "price": null, "changePct": null},
                         {"name": "삼성전자", "code": "005930",
                          "netAmountEok": 1234.5, "price": 70000, "changePct": -1.25}]
                        """)));

        String text = service(radar).fullText();

        assertThat(text).contains("- 가격모름 (000001): 데이터 없음 · 현재가 데이터 없음 (데이터 없음)");
        assertThat(text).doesNotContain("가격모름 (000001): 0.00");
        // 값이 있는 행은 예전과 같은 모양입니다.
        assertThat(text).contains("- 삼성전자 (005930): 1,234.50억원 · 현재가 70,000.00 (-1.25%)");
    }

    /** 레이더 외의 구간은 빈 응답(데이터 없음)으로 둡니다. */
    private static SnapshotTextService service(RadarService radar) {
        return new SnapshotTextService(
                mock(MacroService.class), mock(LiquidityService.class), mock(SectorService.class),
                mock(CotService.class), mock(KrxService.class), radar,
                mock(Sec13FService.class), mock(KrFlowsService.class));
    }
}
