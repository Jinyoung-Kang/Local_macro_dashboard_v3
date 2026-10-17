package com.macrodash.feature.insight;

import com.macrodash.Kst;
import com.macrodash.analytics.Scorecard;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.support.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종목 스코어카드 — 가격 저장본은 목으로 줍니다.
 */
class ScorecardServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> TICKERS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "JPM", "XOM", "KO", "PG", "SPY", "QQQ");

    /**
     * 5년치 평일 종가. 휴장일 대신 평일 20개마다 하루를 빼서, 1년 구간 표본이 실제 미국 시장처럼 252개를
     * 넘지 않게 합니다(365일 창의 거래일은 많아야 252).
     */
    static ObjectNode equityHistory() {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode tickers = payload.putObject("tickers");
        payload.putObject("nameMap");
        LocalDate end = Kst.today().minusDays(1);
        for (int t = 0; t < TICKERS.size(); t++) {
            ObjectNode entry = tickers.putObject(TICKERS.get(t));
            ArrayNode dates = entry.putArray("dates");
            ArrayNode close = entry.putArray("close");
            int weekday = 0;
            int index = 0;
            for (LocalDate date = end.minusYears(5); !date.isAfter(end); date = date.plusDays(1)) {
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                if (weekday++ % 20 == 0) {
                    continue;
                }
                dates.add(date.toString());
                // 종목마다 기울기와 흔들림이 다른 결정적 계열(난수를 쓰면 가끔 실패합니다).
                close.add(100.0 + index * (0.02 + 0.01 * t) + 3.0 * Math.sin(index / (5.0 + t)));
                index++;
            }
        }
        return payload;
    }

    static ScorecardService service() {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_EQUITY_HISTORY), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(Datasets.SNAP_EQUITY_HISTORY, equityHistory(),
                        "json", "ok", null, Instant.now())));
        return new ScorecardService(store);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metric(Map<String, Object> card, String label) {
        return ((List<Map<String, Object>>) card.get("metrics")).stream()
                .filter(row -> label.equals(row.get("label"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("QA-088 기본 구간(1년)에서도 12개월 모멘텀을 잰다 — 측정 구간 자르기는 변동성·낙폭·베타에만")
    void twelveMonthMomentumIsMeasuredInTheDefaultOneYearWindow() {
        Map<String, Object> card = service().scorecard("AAPL", "SPY", 1);

        assertThat(card.get("available")).isEqualTo(true);
        Map<String, Object> momentum = metric(card, "12개월 모멘텀");
        assertThat(momentum.get("value")).as("1년 구간 표본(≤252)만으로는 252 거래일 전 값이 없어 늘 비었음").isNotNull();
        assertThat(momentum.get("score")).as("유니버스도 같은 방식이라 백분위가 나옴").isNotNull();
        assertThat(card.get("scoredCount")).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("QA-088 2년 이상 구간은 값·백분위가 예전(구간으로 자른 시계열만으로 잰 값)과 같다")
    void twoYearsAndLongerWindowsAreUnchanged() {
        ObjectNode payload = equityHistory();
        ScorecardService service = service();
        for (int years : new int[]{2, 3, 5}) {
            // 예전 계산: 종목·기준·유니버스 모두 측정 구간으로 자른 시계열만으로 잽니다.
            LocalDate cutoff = Kst.today().minusYears(years);
            NavigableMap<LocalDate, Double> aapl = Json.dateValueSeries(payload.get("tickers").get("AAPL"), "dates", "close", cutoff);
            NavigableMap<LocalDate, Double> spy = Json.dateValueSeries(payload.get("tickers").get("SPY"), "dates", "close", cutoff);
            Scorecard.Raw before = Scorecard.measure(aapl, spy);
            List<Double> universe = new ArrayList<>();
            for (String ticker : TICKERS) {
                universe.add(Scorecard.measure(Json.dateValueSeries(
                        payload.get("tickers").get(ticker), "dates", "close", cutoff), null).momentum12m());
            }

            Map<String, Object> card = service.scorecard("AAPL", "SPY", years);

            Map<String, Object> momentum = metric(card, "12개월 모멘텀");
            assertThat(momentum.get("value")).as("%d년 구간 12개월 모멘텀", years).isEqualTo(before.momentum12m());
            assertThat(momentum.get("score")).isEqualTo(Scorecard.percentileScore(before.momentum12m(), universe, true));
            assertThat(metric(card, "3개월 모멘텀").get("value")).isEqualTo(before.momentum3m());
            assertThat(metric(card, "변동성 (연율)").get("value")).isEqualTo(before.volatility());
            assertThat(metric(card, "최대낙폭").get("value")).isEqualTo(before.maxDrawdown());
            assertThat(metric(card, "추세 위치").get("value")).isEqualTo(before.trendPosition());
            Map<String, Object> raw = (Map<String, Object>) card.get("raw");
            assertThat(raw.get("momentum1m")).isEqualTo(before.momentum1m());
            assertThat(raw.get("momentum6m")).isEqualTo(before.momentum6m());
            assertThat(raw.get("beta")).isEqualTo(before.beta());
            assertThat(raw.get("samples")).isEqualTo(before.samples());
        }
    }
}
