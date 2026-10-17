package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 구루 스타일 · 포트폴리오 위험 · 스코어카드의 계산 규칙.
 *
 * <p>세 묶음이 공유하는 원칙은 하나입니다 — <b>모르는 것은 null</b>. 표본이
 * 부족하거나 짝이 맞지 않으면 그럴듯한 숫자를 만들지 않습니다.
 */
class GuruAndRiskTest {

    @Nested
    @DisplayName("GuruStyle")
    class GuruStyleTest {

        @Test
        @DisplayName("겹침 비중은 각 종목에서 작은 쪽을 더한다")
        void overlapTakesTheSmallerSide() {
            Map<String, Double> berkshire = Map.of("AAPL", 40.0, "KO", 10.0, "BAC", 10.0);
            Map<String, Double> other = Map.of("AAPL", 15.0, "KO", 20.0, "MSFT", 30.0);

            // 애플 min(40,15)=15 · 코카콜라 min(10,20)=10 · 나머지는 겹치지 않음
            assertThat(GuruStyle.overlapWeight(berkshire, other)).isEqualTo(25.0);
        }

        @Test
        @DisplayName("공통 종목이 없으면 유사도는 0")
        void noSharedHoldingsMeansZero() {
            Map<String, Double> a = Map.of("AAPL", 100.0);
            Map<String, Double> b = Map.of("MSFT", 100.0);

            assertThat(GuruStyle.overlapWeight(a, b)).isZero();
            assertThat(GuruStyle.cosine(a, b)).isZero();
        }

        @Test
        @DisplayName("유효 종목 수는 쏠림을 드러낸다 — 종목 수만 세면 안 되는 이유")
        void effectiveHoldingsExposeConcentration() {
            // 100종목이지만 한 종목이 90%
            Map<String, Double> concentrated = new LinkedHashMap<>();
            concentrated.put("BIG", 90.0);
            for (int i = 0; i < 99; i++) {
                concentrated.put("SMALL" + i, 10.0 / 99.0);
            }
            // 10종목 균등
            Map<String, Double> even = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                even.put("EVEN" + i, 10.0);
            }

            assertThat(concentrated).hasSize(100);
            assertThat(GuruStyle.effectiveHoldings(concentrated)).isLessThan(2.0);
            assertThat(GuruStyle.effectiveHoldings(even)).isCloseTo(10.0, within(0.01));
        }

        @Test
        @DisplayName("회전율은 2로 나눈다 — 갈아탄 것을 두 번 세지 않으려고")
        void turnoverHalvesTheSum() {
            Map<String, Double> before = Map.of("A", 50.0, "B", 50.0);
            Map<String, Double> after = Map.of("A", 45.0, "B", 55.0);

            // |−5| + |+5| = 10 → 실제로 갈아탄 것은 5%
            assertThat(GuruStyle.turnover(before, after)).isEqualTo(5.0);
        }

        @Test
        @DisplayName("직전 분기가 없으면 회전율은 null (0이 아니다)")
        void turnoverIsNullWithoutPreviousQuarter() {
            // 0으로 두면 "한 주도 안 바꿨다"로 읽힙니다.
            assertThat(GuruStyle.turnover(Map.of(), Map.of("A", 100.0))).isNull();
            assertThat(GuruStyle.turnover(null, Map.of("A", 100.0))).isNull();
        }
    }

    @Nested
    @DisplayName("PortfolioRisk")
    class PortfolioRiskTest {

        @Test
        @DisplayName("비중 가중 수익률은 모든 종목에 값이 있는 날만 쓴다")
        void weightedReturnsUseOnlySharedDays() {
            Map<String, NavigableMap<LocalDate, Double>> prices = new LinkedHashMap<>();
            prices.put("A", series(LocalDate.of(2026, 1, 2), 100.0, 110.0, 121.0));
            // B는 하루 적습니다 — 마지막 날이 없습니다.
            NavigableMap<LocalDate, Double> b = series(LocalDate.of(2026, 1, 2), 50.0, 55.0);
            prices.put("B", b);

            var returns = PortfolioRisk.weightedReturns(Map.of("A", 50.0, "B", 50.0), prices);

            // 공통 날짜 2일 → 수익률 1개. 빠진 날을 0%로 채우지 않습니다.
            assertThat(returns.size()).isEqualTo(1);
            assertThat(returns.values()[0]).isCloseTo(0.10, within(1e-9));
        }

        @Test
        @DisplayName("가격을 못 찾은 종목은 빼고 나머지로 비중을 다시 100%로 맞춘다")
        void rescalesWeightsOverCoveredHoldings() {
            Map<String, NavigableMap<LocalDate, Double>> prices = Map.of(
                    "A", series(LocalDate.of(2026, 1, 2), 100.0, 110.0));

            // B는 가격이 없습니다. A 30% + B 70%지만, A만으로 100%를 맞춥니다.
            var returns = PortfolioRisk.weightedReturns(Map.of("A", 30.0, "B", 70.0), prices);

            assertThat(returns.size()).isEqualTo(1);
            // 0.3 × 10% = 3%가 아니라, A가 전부이므로 10%
            assertThat(returns.values()[0]).isCloseTo(0.10, within(1e-9));
        }

        @Test
        @DisplayName("베타 1 — 벤치마크와 똑같이 움직이면")
        void betaOfIdenticalSeriesIsOne() {
            double[] market = wave(60, 0.01);
            assertThat(PortfolioRisk.beta(market, market)).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("벤치마크를 그대로 따라가면 추적오차는 0")
        void trackingErrorIsZeroWhenIdentical() {
            double[] market = wave(60, 0.01);
            assertThat(PortfolioRisk.trackingError(market, market)).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("표본이 20일 미만이면 VaR·베타는 null")
        void refusesToGuessFromTinySamples() {
            double[] tiny = wave(10, 0.01);
            assertThat(PortfolioRisk.historicalVar(tiny, 0.95)).isNull();
            assertThat(PortfolioRisk.expectedShortfall(tiny, 0.95)).isNull();
            assertThat(PortfolioRisk.beta(tiny, tiny)).isNull();
        }

        @Test
        @DisplayName("ES는 VaR보다 나쁘다 — 넘어선 날들의 평균이므로")
        void expectedShortfallIsWorseThanVar() {
            double[] returns = new double[200];
            for (int i = 0; i < returns.length; i++) {
                returns[i] = (i % 40 == 0) ? -0.08 : 0.004;
            }
            Double var = PortfolioRisk.historicalVar(returns, 0.95);
            Double es = PortfolioRisk.expectedShortfall(returns, 0.95);

            assertThat(var).isNotNull();
            assertThat(es).isNotNull();
            assertThat(es).isLessThanOrEqualTo(var);
        }

        @Test
        @DisplayName("최대낙폭은 고점 대비 하락폭")
        void maxDrawdownMeasuresPeakToTrough() {
            // +100% 뒤 -50% → 원점. 낙폭은 50%
            double[] returns = {1.0, -0.5};
            assertThat(PortfolioRisk.maxDrawdown(returns)).isCloseTo(50.0, within(1e-9));
        }

        @Test
        @DisplayName("위험 기여의 합은 포트폴리오 변동성과 같다")
        void contributionsSumToPortfolioVolatility() {
            Map<String, NavigableMap<LocalDate, Double>> prices = new LinkedHashMap<>();
            prices.put("A", randomWalk(120, 100.0, 7));
            prices.put("B", randomWalk(120, 50.0, 13));
            Map<String, Double> weights = Map.of("A", 60.0, "B", 40.0);

            var portfolio = PortfolioRisk.weightedReturns(weights, prices);
            var contributions = PortfolioRisk.contributions(
                    weights, Map.of(), prices, portfolio);

            double sum = contributions.stream()
                    .mapToDouble(PortfolioRisk.Contribution::contribution).sum();
            // 개별 변동성을 그냥 더하면 분산 효과가 사라져 합이 맞지 않습니다.
            assertThat(sum).isCloseTo(
                    PortfolioRisk.annualizedVolatility(portfolio.values()), within(1e-6));
        }
    }

    @Nested
    @DisplayName("Scorecard")
    class ScorecardTest {

        @Test
        @DisplayName("비교 대상이 10개 미만이면 점수를 내지 않는다")
        void refusesPercentileWithTooFewPeers() {
            List<Double> tiny = List.of(1.0, 2.0, 3.0);
            // 3개로 낸 "67점"은 숫자만 그럴듯하고 아무것도 말하지 않습니다.
            assertThat(Scorecard.percentileScore(2.5, tiny, true)).isNull();
        }

        @Test
        @DisplayName("낮을수록 좋은 지표는 점수가 뒤집힌다")
        void lowerIsBetterInvertsTheScore() {
            List<Double> universe = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                universe.add((double) i);
            }
            // 값 90은 상위 90%에 있지만, 변동성이라면 나쁜 쪽입니다.
            assertThat(Scorecard.percentileScore(90.0, universe, true))
                    .isCloseTo(90.0, within(0.01));
            assertThat(Scorecard.percentileScore(90.0, universe, false))
                    .isCloseTo(10.0, within(0.01));
        }

        @Test
        @DisplayName("모멘텀은 거래일 기준으로 센다")
        void momentumCountsTradingDays() {
            NavigableMap<LocalDate, Double> series = new TreeMap<>();
            LocalDate date = LocalDate.of(2026, 1, 2);
            for (int i = 0; i <= 21; i++) {
                series.put(date.plusDays(i), 100.0 + i);
            }
            // 21 거래일 전은 100, 지금은 121 → +21%
            assertThat(Scorecard.momentum(series, 21)).isCloseTo(21.0, within(1e-9));
        }

        @Test
        @DisplayName("표본이 30일 미만이면 전부 null")
        void refusesToMeasureShortSeries() {
            NavigableMap<LocalDate, Double> series = series(LocalDate.of(2026, 1, 2), 100.0, 101.0);
            Scorecard.Raw raw = Scorecard.measure(series, null);

            assertThat(raw.volatility()).isNull();
            assertThat(raw.momentum12m()).isNull();
            assertThat(raw.maxDrawdown()).isNull();
        }

        @Test
        @DisplayName("QA-088 모멘텀·추세는 전체 시계열로 — 1년 구간에서도 12개월 모멘텀이 나오고, 2년 이상 구간은 값이 그대로")
        void recentTradingDayMetricsUseTheWholeHistory() {
            NavigableMap<LocalDate, Double> full = new TreeMap<>();
            LocalDate start = LocalDate.of(2021, 1, 4);
            for (int i = 0; i < 5 * 250; i++) {
                full.put(start.plusDays(i * 73L / 50), 100.0 + i * 0.05 + 4.0 * Math.sin(i / 9.0));
            }
            LocalDate end = full.lastKey();

            NavigableMap<LocalDate, Double> oneYear = full.tailMap(end.minusYears(1), true);
            assertThat(oneYear.size()).as("1년 구간 표본").isLessThanOrEqualTo(252);
            assertThat(Scorecard.measure(oneYear, null).momentum12m()).as("구간만 보면 비는 값").isNull();
            Scorecard.Raw measured = Scorecard.measure(full, oneYear, null);
            assertThat(measured.momentum12m()).isNotNull();
            assertThat(measured.samples()).as("표본 수는 측정 구간").isEqualTo(oneYear.size() - 1);

            for (int years : new int[]{2, 3, 4}) {
                NavigableMap<LocalDate, Double> window = full.tailMap(end.minusYears(years), true);
                assertThat(Scorecard.measure(full, window, null))
                        .as("%d년 구간 — 예전(구간만으로 잰 값)과 같음", years)
                        .isEqualTo(Scorecard.measure(window, null));
            }
        }
    }

    // ------------------------------------------------------------ 도우미
    private static NavigableMap<LocalDate, Double> series(LocalDate start, double... values) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        for (int i = 0; i < values.length; i++) {
            out.put(start.plusDays(i), values[i]);
        }
        return out;
    }

    /** 결정적인 톱니 수익률. 난수를 쓰면 테스트가 가끔 실패합니다. */
    private static double[] wave(int size, double amplitude) {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = Math.sin(i) * amplitude;
        }
        return out;
    }

    private static NavigableMap<LocalDate, Double> randomWalk(int days, double start, int seed) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        double price = start;
        for (int i = 0; i < days; i++) {
            price *= 1.0 + Math.sin(i * seed * 0.37) * 0.02;
            out.put(date.plusDays(i), price);
        }
        return out;
    }
}
