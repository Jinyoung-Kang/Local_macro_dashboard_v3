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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 원본 텍스트(AI 입력·복사용)의 섹터·자산군 모멘텀 구간.
 *
 * <p>수집 실패로 직전 계열을 이어받은 ETF(isStale, 최대 3일)는 수익률이 그만큼 멈춰 있습니다. 업종 화면은 '⚠️ 이전 값'을
 * 그리는데(QA-065), AI 입력 텍스트에는 표시 없이 '직전 거래일 마감 기준'으로 들어갔습니다(QA-095).
 */
class SnapshotTextSectorTest {

    private static Map<String, Object> row(String name, String ticker, double w1, double m1, double m3,
                                           boolean stale) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticker", ticker);
        row.put("name", name);
        row.put("returns", Map.of("1W", w1, "1M", m1, "3M", m3));
        row.put("isStale", stale);
        row.put("staleReason", stale ? "yfinance 응답 없음" : null);
        return row;
    }

    private static SnapshotTextService service(SectorService sector) {
        return new SnapshotTextService(mock(MacroService.class), mock(LiquidityService.class), sector,
                mock(CotService.class), mock(KrxService.class), mock(RadarService.class),
                mock(Sec13FService.class), mock(KrFlowsService.class));
    }

    @Test
    @Disabled("QA-095: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-095: 이어받은(이전 값) 섹터·자산군 행에는 매크로 카드와 같은 '⚠️ 이전 값' 꼬리를 붙인다")
    void staleRotationRowsAreMarked() {
        SectorService sector = mock(SectorService.class);
        Map<String, Object> rotation = new LinkedHashMap<>();
        rotation.put("available", true);
        rotation.put("collectedAtKst", "2026-10-18 10:00 KST");
        rotation.put("sectors", List.of(
                row("에너지 (Energy)", "XLE", 3.10, 5.0, 12.0, true),
                row("정보기술 (Technology)", "XLK", 1.0, 2.0, 8.0, false)));
        rotation.put("assetClasses", List.of(row("금 (Gold)", "GLD", 0.5, 1.5, 6.0, true)));
        when(sector.rotation("3M")).thenReturn(rotation);

        String text = service(sector).fullText();

        assertThat(text).contains("- 에너지 (Energy) (XLE): 1주 +3.10% · 1개월 +5.00% · 3개월 +12.00%"
                + " ⚠️ 이전 값(이번 수집 실패: yfinance 응답 없음)");
        assertThat(text).contains("- 금 (Gold) (GLD): 1주 +0.50% · 1개월 +1.50% · 3개월 +6.00%"
                + " ⚠️ 이전 값(이번 수집 실패: yfinance 응답 없음)");
        assertThat(text).as("새로 받은 행은 예전과 같은 모양")
                .contains("- 정보기술 (Technology) (XLK): 1주 +1.00% · 1개월 +2.00% · 3개월 +8.00%\n");
    }
}
