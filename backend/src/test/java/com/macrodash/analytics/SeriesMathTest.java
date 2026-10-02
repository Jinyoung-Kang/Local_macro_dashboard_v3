package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 시계열 계산 회귀 테스트.
 *
 * <p>핵심: <b>표본 부족은 0.0이 아니라 null</b>입니다. 화면에서 "0.00%"는
 * '데이터 없음'이 아니라 '보합'으로 읽히고, 순위 계산에도 섞여 들어갑니다.
 */
class SeriesMathTest {

    @Test
    @DisplayName("기간 수익률 계산")
    void periodReturn() {
        List<Double> closes = List.of(100.0, 102.0, 105.0, 110.0);

        assertThat(SeriesMath.periodReturn(closes, 3)).isCloseTo(10.0, within(0.001));
        assertThat(SeriesMath.periodReturn(closes, 1)).isCloseTo(4.7619, within(0.001));
    }

    @Test
    @DisplayName("표본이 부족하면 0.0이 아니라 null")
    void insufficientSampleReturnsNull() {
        List<Double> closes = List.of(100.0, 102.0);

        assertThat(SeriesMath.periodReturn(closes, 252)).isNull();
        assertThat(SeriesMath.periodReturn(List.of(), 5)).isNull();
    }

    @Test
    @DisplayName("과거 가격이 0이면 null (무한대나 0.0으로 만들지 않음)")
    void zeroBaseReturnsNull() {
        assertThat(SeriesMath.periodReturn(List.of(0.0, 100.0), 1)).isNull();
    }

    @Test
    @DisplayName("YTD는 해당 연도 첫 표본 기준")
    void yearToDateReturn() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2025, 12, 30),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 9, 11));
        List<Double> closes = List.of(90.0, 100.0, 120.0);

        assertThat(SeriesMath.yearToDateReturn(dates, closes, 2026)).isCloseTo(20.0, within(0.001));
        assertThat(SeriesMath.yearToDateReturn(dates, closes, 2024)).isNull();
    }

    @Test
    @DisplayName("백분위는 표본 내 위치")
    void percentile() {
        assertThat(SeriesMath.percentile(List.of(1.0, 2.0, 3.0, 4.0)))
                .isCloseTo(100.0, within(0.001));
        assertThat(SeriesMath.percentile(List.of(4.0, 3.0, 2.0, 1.0)))
                .isCloseTo(25.0, within(0.001));
        assertThat(SeriesMath.percentile(List.of())).isNull();
    }

    @Test
    @DisplayName("차이 계산에서 결측은 전파된다 (0으로 메우지 않음)")
    void differencePropagatesNull() {
        assertThat(SeriesMath.difference(3.0, 1.0)).isCloseTo(2.0, within(0.001));
        assertThat(SeriesMath.difference(null, 1.0)).isNull();
        assertThat(SeriesMath.difference(3.0, null)).isNull();
    }

    @Test
    @DisplayName("변화율은 기준값이 0이면 null")
    void percentChangeGuardsZero() {
        assertThat(SeriesMath.percentChange(110.0, 100.0)).isCloseTo(10.0, within(0.001));
        assertThat(SeriesMath.percentChange(110.0, 0.0)).isNull();
        assertThat(SeriesMath.percentChange(null, 100.0)).isNull();
    }

    @Test
    @DisplayName("구간 내 위치(COT OI Index와 같은 계산)")
    void rangePosition() {
        List<Double> window = List.of(100.0, 200.0, 300.0);

        assertThat(SeriesMath.rangePosition(window, 300.0)).isCloseTo(100.0, within(0.001));
        assertThat(SeriesMath.rangePosition(window, 100.0)).isCloseTo(0.0, within(0.001));
        assertThat(SeriesMath.rangePosition(window, 200.0)).isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("기간 문자열 해석")
    void periodDays() {
        assertThat(SeriesMath.periodDays("1mo")).isEqualTo(31);
        assertThat(SeriesMath.periodDays("5y")).isEqualTo(1827);
        assertThat(SeriesMath.periodDays("max")).isNull();
    }

    @Test
    @DisplayName("n주 변화는 날짜로 재고, 기준일에 값이 없으면 그 앞의 가장 가까운 값을 쓴다")
    void changeOverWeeksUsesDates() {
        java.util.NavigableMap<LocalDate, Double> weekly = new java.util.TreeMap<>();
        weekly.put(LocalDate.of(2026, 1, 7), 100.0);    // 수요일 발표 계열
        weekly.put(LocalDate.of(2026, 1, 14), 110.0);
        weekly.put(LocalDate.of(2026, 1, 21), 120.0);
        weekly.put(LocalDate.of(2026, 2, 4), 150.0);    // 1/28 결측

        assertThat(SeriesMath.changeOverWeeks(weekly, 4)).isEqualTo(50.0);      // 2/4 − 1/7
        assertThat(SeriesMath.changeOverWeeks(weekly, 2)).isEqualTo(30.0);      // 2/4 − (1/21: 1/21 기준일 그대로)
        assertThat(SeriesMath.changeOverWeeks(weekly, 1)).isEqualTo(30.0);      // 1/28 결측 → 1/21 값
        assertThat(SeriesMath.changeOverWeeks(weekly, 8)).as("8주 전 값 없음").isNull();
        assertThat(SeriesMath.changeOverWeeks(new java.util.TreeMap<>(), 4)).isNull();
    }
}
