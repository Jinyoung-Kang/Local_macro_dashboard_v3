package com.macrodash.feature.snapshot;

import com.macrodash.feature.institution.Sec13FService;
import com.macrodash.feature.macro.LiquidityService;
import com.macrodash.feature.macro.MacroService;
import com.macrodash.feature.macro.SectorService;
import com.macrodash.feature.positioning.CotService;
import com.macrodash.feature.positioning.KrFlowsService;
import com.macrodash.feature.positioning.KrxService;
import com.macrodash.feature.positioning.RadarService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI에 넘기는 원본 텍스트의 거시·유동성 구간.
 *
 * <p>Claude 리포트가 두 가지를 "불일치"로 짚었습니다. 하나는 실제 결함(금리 출처 성격을 잘못 적음),
 * 하나는 AI의 단위 환산 실수(역레포 23.4억 달러 → 0.0234조로 읽음)였습니다.
 */
class SnapshotTextMacroTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SnapshotTextService service(MacroService macro, LiquidityService liquidity) {
        return new SnapshotTextService(macro, liquidity, mock(SectorService.class),
                mock(CotService.class), mock(KrxService.class), mock(RadarService.class),
                mock(Sec13FService.class), mock(KrFlowsService.class));
    }

    @Test
    @Disabled("수정 전 — 머리말이 미국채 금리를 FRED 확정치로 적습니다")
    @DisplayName("미국채 금리 카드는 TradingView 참고 시세다 — 머리말이 'FRED 확정치'라고 적으면 안 된다")
    void treasuryYieldsAreNotLabeledAsFredOfficial() throws Exception {
        MacroService macro = mock(MacroService.class);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("available", true);
        overview.put("collectedAtKst", "2026-10-08 16:10 KST");
        overview.put("categories", MAPPER.readTree("""
                [{"title":"🏛️ 미국 국채 수익률","items":[
                  {"name":"미국채 10년물 수익률(%)","status":"ok","priceStr":"5.326","deltaStr":"+0.040 (+0.76%)",
                   "prevStr":"5.286","prevSource":"TradingView"}]}]
                """));
        overview.put("spreads", Map.of(
                "realtime", Map.of("longKey", "us10y", "shortKey", "us02y", "longValue", 5.33,
                        "shortValue", 4.79, "spread", 0.53, "delta", 0.02),
                "official10y2y", Map.of("latest", 0.48)));
        when(macro.overview()).thenReturn(overview);

        String text = service(macro, mock(LiquidityService.class)).fullText();

        assertThat(text).doesNotContain("미국채 금리는 FRED 일별 확정치");
        assertThat(text).contains("미국채 금리는 TradingView 참고 시세");
        assertThat(text).contains("- 10Y-2Y 스프레드 (TradingView 참고 시세로 계산): 0.53%p");
        assertThat(text).contains("- 공식 일별 10Y-2Y (FRED 확정치, 하루 지연): 0.48%p");
    }

    @Test
    @Disabled("수정 전 — 구성 항목이 조·억으로 섞여 있습니다")
    @DisplayName("순유동성 구성 항목을 같은 단위(조 달러)로 함께 적어, 다시 계산해도 맞게 한다")
    void liquidityComponentsShareOneUnit() {
        LiquidityService liquidity = mock(LiquidityService.class);
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("date", "2026-10-07");
        latest.put("netLiquidityT", 5.792019);
        latest.put("deltaT", -0.001924);
        latest.put("walclT", 6.743031);
        latest.put("tgaB", 948.674);
        latest.put("rrpB", 2.338);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("isEstimated", false);
        result.put("latest", latest);
        when(liquidity.netLiquidity(3)).thenReturn(result);

        String text = service(mock(MacroService.class), liquidity).fullText();

        assertThat(text).contains("- 역레포(ON RRP): 23.4억 달러 (= 0.0023조 달러)");
        assertThat(text).contains("- 재무부 일반계정(TGA): 9,487억 달러 (= 0.9487조 달러)");
        assertThat(text).contains("- 계산 (조 달러, 각 값 반올림): 6.7430 − 0.9487 − 0.0023 = 5.7920");
    }
}
