package com.macrodash.feature.alert;

import com.macrodash.analytics.AlertRules;
import com.macrodash.analytics.FlowRecord;
import com.macrodash.analytics.Holding;
import com.macrodash.store.AlertStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CurrentUser FREE = new CurrentUser(1, "f@x", "MEMBER", "FREE", null);
    private static final CurrentUser PRO = new CurrentUser(2, "p@x", "MEMBER", "PRO", null);

    @Test
    @DisplayName("무료는 국면 전환 1개만, 유료는 종류·대상 검사를 통과해야 만든다")
    void rulesByTier() {
        AlertStore store = mock(AlertStore.class);
        AlertService service = new AlertService(store, MAPPER);

        assertThat(service.create(FREE, "FOREIGN_STREAK", MAPPER.createObjectNode().put("target", "KOSPI").put("n", 5))
                .status()).isEqualTo(403);
        when(store.countRules(1)).thenReturn(1);
        assertThat(service.create(FREE, "REGIME_CHANGE", MAPPER.createObjectNode()).status()).as("상한 1개").isEqualTo(400);

        assertThat(service.create(PRO, "FOREIGN_STREAK", MAPPER.createObjectNode().put("target", "KOSPI").put("n", 2))
                .status()).as("N은 3~20").isEqualTo(400);
        assertThat(service.create(PRO, "FOREIGN_STREAK", MAPPER.createObjectNode().put("target", "STOCK").put("code", "59"))
                .status()).isEqualTo(400);
        assertThat(service.create(PRO, "INSTITUTION_NEW", MAPPER.createObjectNode().put("cik", "999")).status())
                .as("추적 기관만").isEqualTo(400);
        assertThat(service.create(PRO, "FOREIGN_STREAK", MAPPER.createObjectNode().put("target", "KOSPI").put("n", 5))
                .status()).isEqualTo(200);
        verify(store).insertRule(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("관심 종목이 수집 범위(전체 60종목)를 넘으면 만들 때 거절 — 받아 두고 조용히 안 보내지 않음")
    void stockUniverseCap() {
        AlertStore store = mock(AlertStore.class);
        when(store.countStockCodes(null)).thenReturn(60);
        when(store.hasStockCode("005930")).thenReturn(false);
        AlertService service = new AlertService(store, MAPPER);

        assertThat(service.create(PRO, "FOREIGN_STREAK",
                MAPPER.createObjectNode().put("target", "STOCK").put("code", "005930").put("n", 5)).message())
                .contains("수집 범위 초과");
        verify(store, never()).insertRule(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("확정 기록만 — 시장은 오늘 기록을 빼고, 종목은 저녁 확정 칸이 모두 찬 오늘 기록만 쓴다")
    void finalizedRecordsOnly() {
        LocalDate today = LocalDate.of(2026, 10, 9);
        FlowRecord.Amounts plus = new FlowRecord.Amounts(10L, 5L, 5L);
        FlowRecord yesterday = new FlowRecord("2026-10-08", null, Map.of("foreigner", plus), Map.of(), null);
        FlowRecord todayPartial = new FlowRecord("2026-10-09", null, Map.of("foreigner", plus), Map.of(), null);
        FlowRecord todayComplete = new FlowRecord("2026-10-09", null,
                Map.of("foreigner", plus, "individual", plus, "otherCorporation", plus),
                Map.of("pensionFund", plus), 0.5);

        assertThat(AlertEvaluator.finalizedForeignNet(List.of(todayPartial, yesterday), today, false))
                .extracting(AlertRules.DayNet::date).containsExactly(LocalDate.of(2026, 10, 8));
        assertThat(AlertEvaluator.finalizedForeignNet(List.of(todayPartial, yesterday), today, true))
                .as("종목도 잠정 칸이 비면 제외").hasSize(1);
        assertThat(AlertEvaluator.finalizedForeignNet(List.of(todayComplete, yesterday), today, true)).hasSize(2);
    }

    @Test
    @DisplayName("알림 문구에 매수·매도 같은 행동 지시가 없다(근거·기준일·한계만)")
    void noActionWords() {
        List<String> bodies = List.of(
                AlertText.regime(new AlertRules.RegimeChange(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 7),
                        "EXPANSION", "확장 (Expansion)", "LATE", "후퇴 경계 (Late cycle)"), Map.of(), 2).body(),
                AlertText.foreignStreak("코스피", false, new AlertRules.StreakHit(LocalDate.of(2026, 10, 9), 5),
                        1_234_000_000_000L).body(),
                AlertText.institutionNew("버크셔", "2026-06-30", "2026-08-14",
                        List.of(new Holding("NVIDIA CORP", "x", "COM", 1.0, 1.0, 2.5))).body());
        for (String body : bodies) {
            for (String banned : List.of("매수하세요", "매도하세요", "사세요", "파세요", "추천", "목표가", "비중을 늘", "비중을 줄")) {
                assertThat(body).doesNotContain(banned);
            }
            assertThat(body).contains("투자 권유가 아닙니다");
        }
        assertThat(AlertText.won(1_234_000_000_000L)).isEqualTo("+1.23조 원");
        assertThat(AlertText.won(-5_600_000_000L)).isEqualTo("-56.0억 원");
    }
}
