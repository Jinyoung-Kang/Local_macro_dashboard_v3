package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 국면 판정 성과의 규칙 — 발표 전 값을 쓰지 않는다, 진입은 다음 거래일, 표본 부족, 시작 시점의 정의.
 */
class RegimeBacktestTest {

    private static final LocalDate THU = LocalDate.of(2026, 1, 8);   // 목요일

    @Test
    @DisplayName("NFCI는 금요일 값이 다음 주 수요일에야 보인다 — 그 전 목요일 판정에는 쓰지 않는다")
    void nfciIsNotUsedBeforeRelease() {
        NavigableMap<LocalDate, Double> nfci = new TreeMap<>();
        nfci.put(LocalDate.of(2026, 1, 2), -0.5);    // 금 → 1/7(수) 발표
        nfci.put(LocalDate.of(2026, 1, 9), 0.3);     // 금 → 1/14(수) 발표

        NavigableMap<LocalDate, Double> known = PointInTime.asKnownOn(nfci, PointInTime.Release.NEXT_WEDNESDAY);

        assertThat(SeriesMath.valueAsOf(known, LocalDate.of(2026, 1, 13))).as("화요일엔 아직 1/2 값").isEqualTo(-0.5);
        assertThat(SeriesMath.valueAsOf(known, LocalDate.of(2026, 1, 14))).as("수요일부터 1/9 값").isEqualTo(0.3);
        assertThat(SeriesMath.valueAsOf(known, LocalDate.of(2026, 1, 6))).as("첫 발표 전엔 모름").isNull();
    }

    @Test
    @DisplayName("일별 값은 다음 영업일에 보인다 — 금요일 값은 월요일")
    void dailyValuesNextBusinessDay() {
        assertThat(PointInTime.availableOn(LocalDate.of(2026, 1, 9), PointInTime.Release.NEXT_BUSINESS_DAY))
                .isEqualTo(LocalDate.of(2026, 1, 12));
        assertThat(PointInTime.availableOn(LocalDate.of(2026, 1, 7), PointInTime.Release.NEXT_BUSINESS_DAY))
                .isEqualTo(LocalDate.of(2026, 1, 8));
    }

    private static NavigableMap<LocalDate, Double> flat(LocalDate from, int days, double value) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        for (int i = 0; i < days; i++) {
            out.put(from.plusDays(i), value);
        }
        return out;
    }

    @Test
    @DisplayName("판정은 매주 목요일, 입력이 하나라도 없으면 판정 불가")
    void timelineOnThursdaysAndUnknownWithoutInputs() {
        LocalDate start = LocalDate.of(2025, 12, 1);
        NavigableMap<LocalDate, Double> curve = flat(start, 120, 0.5);
        NavigableMap<LocalDate, Double> nfci = flat(start, 120, -0.4);
        NavigableMap<LocalDate, Double> liquidity = new TreeMap<>();
        for (int i = 0; i < 120; i++) {
            liquidity.put(start.plusDays(i), 6.0 + i * 0.001);   // 늘 늘어남 → 확대
        }
        NavigableMap<LocalDate, Double> hy = flat(LocalDate.of(2026, 1, 20), 60, 3.0);   // 하이일드는 나중에 시작

        List<RegimeBacktest.Point> timeline = RegimeBacktest.timeline(curve, nfci, hy, liquidity,
                LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 28));

        assertThat(timeline).allSatisfy(p -> assertThat(p.date().getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY));
        assertThat(timeline.get(0).code()).as("하이일드가 없던 주").isEqualTo("UNKNOWN");
        assertThat(timeline.get(timeline.size() - 1).code()).isEqualTo("EXPANSION");
    }

    @Test
    @DisplayName("진입은 판정 다음 거래일 종가 — 판정한 목요일 종가가 아니다")
    void entryIsNextTradingDay() {
        NavigableMap<LocalDate, Double> prices = new TreeMap<>();
        prices.put(THU, 100.0);
        prices.put(THU.plusDays(1), 110.0);               // 금: 진입
        prices.put(THU.plusDays(1).plusWeeks(4), 121.0);  // 진입 4주 뒤
        List<RegimeBacktest.Point> timeline = List.of(new RegimeBacktest.Point(THU, "EXPANSION", "확장"));

        RegimeBacktest.Result result = RegimeBacktest.trackRecord(timeline, prices, 4);

        assertThat(result.baseline().get(0).summary().mean()).isEqualTo(10.0, within(1e-9));
    }

    private static List<RegimeBacktest.Point> weeks(String... codes) {
        List<RegimeBacktest.Point> out = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            out.add(new RegimeBacktest.Point(THU.plusWeeks(i), codes[i], codes[i]));
        }
        return out;
    }

    @Test
    @DisplayName("국면 시작은 직전 '유효' 판정과 다를 때만 — 판정 불가 다음 주는 시작인지 모른다")
    void startsNeedAKnownPreviousVerdict() {
        List<RegimeBacktest.Point> timeline = weeks("UNKNOWN", "EXPANSION", "EXPANSION", "LATE", "LATE", "EXPANSION");

        assertThat(RegimeBacktest.starts(timeline, "EXPANSION")).extracting(RegimeBacktest.Point::date)
                .containsExactly(THU.plusWeeks(5));
        assertThat(RegimeBacktest.starts(timeline, "LATE")).extracting(RegimeBacktest.Point::date)
                .containsExactly(THU.plusWeeks(3));
        assertThat(RegimeBacktest.episodes(timeline, "EXPANSION")).isEqualTo(2);
    }

    @Test
    @DisplayName("기간이 겹치는 연속 표본은 독립 표본 하나로 센다 — 표본 수와 따로 낸다")
    void independentCountsNonOverlapping() {
        List<RegimeBacktest.Point> points = weeks("A", "A", "A", "A", "A", "A", "A", "A", "A");
        List<Double> returns = new ArrayList<>(List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0));

        assertThat(RegimeBacktest.independent(points, returns, 4)).as("0·4·8주째").isEqualTo(3);
        returns.set(0, null);
        assertThat(RegimeBacktest.independent(points, returns, 4)).as("1·5주째 (9주 중 수익률 모르는 첫 주 제외)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("표본이 적으면 '부족' — 숫자는 계산하되 sufficient=false, 기준선과의 차이는 함께 낸다")
    void insufficientSamplesAreFlagged() {
        NavigableMap<LocalDate, Double> prices = new TreeMap<>();
        for (int i = 0; i < 400; i++) {
            prices.put(THU.plusDays(i), 100.0 + i);
        }
        List<RegimeBacktest.Point> timeline = weeks("EXPANSION", "EXPANSION", "LATE", "LATE", "EXPANSION");

        RegimeBacktest.Result result = RegimeBacktest.trackRecord(timeline, prices, 4);

        RegimeBacktest.RegimeStats expansion = result.all().stream()
                .filter(s -> s.code().equals("EXPANSION")).findFirst().orElseThrow();
        assertThat(expansion.cells().get(0).summary().count()).isEqualTo(3);
        assertThat(expansion.cells().get(0).sufficient()).isFalse();
        assertThat(expansion.cells().get(0).excessMean()).isNotNull();
        assertThat(result.all()).extracting(RegimeBacktest.RegimeStats::code)
                .containsExactly("EXPANSION", "LATE", "RECOVERY", "CONTRACTION");
        RegimeBacktest.RegimeStats recovery = result.all().get(2);
        assertThat(recovery.cells().get(0).summary().mean()).as("없는 국면은 0이 아니라 null").isNull();
    }
}
