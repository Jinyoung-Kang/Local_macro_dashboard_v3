package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 상관·국면·COT 백테스트의 계산 규칙.
 *
 * <p>이 세 가지는 "숫자가 그럴듯하게 보이는" 쪽으로 틀리기 쉬운 계산입니다.
 * 빠진 값을 0으로 메우거나, 미래 정보를 써서 과거를 판정하거나, 표본이 없는데도
 * 결론을 내는 실수를 테스트로 막아 둡니다.
 */
class AnalyticsMathTest {

    @Nested
    @DisplayName("상관계수")
    class CorrelationTest {

        @Test
        @DisplayName("완전한 양·음의 상관을 정확히 계산한다")
        void perfectCorrelations() {
            double[] x = {1, 2, 3, 4, 5};
            double[] up = {2, 4, 6, 8, 10};
            double[] down = {10, 8, 6, 4, 2};

            assertThat(Correlation.pearson(x, up)).isEqualTo(1.0, within(1e-9));
            assertThat(Correlation.pearson(x, down)).isEqualTo(-1.0, within(1e-9));
        }

        @Test
        @DisplayName("한쪽이 상수면 0이 아니라 null이다 — '상관 없음'과 '계산 불가'는 다르다")
        void constantSeriesIsUnknownNotZero() {
            assertThat(Correlation.pearson(new double[]{1, 2, 3}, new double[]{5, 5, 5})).isNull();
        }

        @Test
        @DisplayName("표본이 3개 미만이면 계산하지 않는다")
        void tooFewSamples() {
            assertThat(Correlation.pearson(new double[]{1, 2}, new double[]{2, 4})).isNull();
        }

        @Test
        @DisplayName("겹치는 날짜만 남긴다 — 발표 주기가 다르면 표본이 줄어든다")
        void alignKeepsOnlySharedDates() {
            TreeMap<LocalDate, Double> daily = new TreeMap<>();
            TreeMap<LocalDate, Double> weekly = new TreeMap<>();
            for (int i = 0; i < 10; i++) {
                daily.put(LocalDate.of(2026, 1, 1).plusDays(i), (double) i);
            }
            weekly.put(LocalDate.of(2026, 1, 1), 1.0);
            weekly.put(LocalDate.of(2026, 3, 1), 2.0);   // daily 범위 밖

            Correlation.Aligned aligned = Correlation.align(daily, weekly);
            assertThat(aligned.size()).isEqualTo(1);
            assertThat(aligned.dates()).containsExactly(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("변화(Δ)로 바꾸면 표본이 하나 줄고 추세는 사라진다")
        void changesRemoveTrend() {
            TreeMap<LocalDate, Double> a = new TreeMap<>();
            TreeMap<LocalDate, Double> b = new TreeMap<>();
            // 둘 다 우상향이지만 변화는 서로 반대입니다.
            double[] left = {10, 12, 13, 16, 17, 21};
            double[] right = {100, 130, 141, 151, 190, 193};
            for (int i = 0; i < left.length; i++) {
                LocalDate date = LocalDate.of(2026, 1, 1).plusDays(i);
                a.put(date, left[i]);
                b.put(date, right[i]);
            }

            Correlation.Aligned levels = Correlation.align(a, b);
            Correlation.Aligned changes = Correlation.toChanges(levels);

            assertThat(changes.size()).isEqualTo(levels.size() - 1);
            // 수준 상관은 추세 때문에 높게 나옵니다.
            assertThat(Correlation.pearson(levels.x(), levels.y())).isGreaterThan(0.9);
            // 변화 상관은 그보다 훨씬 낮습니다 — 이것이 기본값을 변화로 둔 이유입니다.
            assertThat(Correlation.pearson(changes.x(), changes.y()))
                    .isLessThan(Correlation.pearson(levels.x(), levels.y()));
        }

        @Test
        @DisplayName("롤링 상관은 창을 못 채운 앞부분을 0이 아니라 null로 둔다")
        void rollingLeavesHeadUnknown() {
            double[] x = {1, 2, 3, 4, 5, 6};
            double[] y = {2, 4, 6, 8, 10, 12};

            List<Double> rolling = Correlation.rolling(x, y, 3);

            assertThat(rolling).hasSize(6);
            assertThat(rolling.get(0)).isNull();
            assertThat(rolling.get(1)).isNull();
            assertThat(rolling.get(2)).isEqualTo(1.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("시장 국면")
    class RegimeTest {

        @Test
        @DisplayName("입력이 하나라도 없으면 판정하지 않는다")
        void missingInputMeansUnknown() {
            Regime.Verdict verdict = Regime.classify(0.5, -0.2, 3.4, null);

            assertThat(verdict.code()).isEqualTo("UNKNOWN");
            assertThat(verdict.known()).isFalse();
            assertThat(verdict.missing()).hasSize(1);
            // 있는 신호는 그대로 보여 줍니다 — 판정만 하지 않습니다.
            assertThat(verdict.signals()).hasSize(3);
        }

        @Test
        @DisplayName("성장·신용 양호 + 유동성 확대 = 확장")
        void expansion() {
            Regime.Verdict verdict = Regime.classify(0.8, -0.4, 3.2, 0.05);

            assertThat(verdict.code()).isEqualTo("EXPANSION");
            assertThat(verdict.growthAxis()).isEqualTo("양호");
            assertThat(verdict.liquidityAxis()).isEqualTo("확대");
        }

        @Test
        @DisplayName("성장·신용 양호 + 유동성 축소 = 후퇴 경계")
        void lateCycle() {
            assertThat(Regime.classify(0.8, -0.4, 3.2, -0.05).code()).isEqualTo("LATE");
        }

        @Test
        @DisplayName("성장·신용 악화 + 유동성 축소 = 침체 경계")
        void contraction() {
            assertThat(Regime.classify(-0.3, 0.5, 7.5, -0.10).code()).isEqualTo("CONTRACTION");
        }

        @Test
        @DisplayName("성장·신용 악화 + 유동성 확대 = 회복")
        void recovery() {
            assertThat(Regime.classify(-0.3, 0.5, 7.5, 0.10).code()).isEqualTo("RECOVERY");
        }

        @Test
        @DisplayName("신호 하나만 나빠도 성장·신용 축은 악화 — 평균으로 희석하지 않는다")
        void oneBadSignalIsEnough() {
            // 금리차·NFCI는 정상인데 하이일드만 경색 수준입니다.
            Regime.Verdict verdict = Regime.classify(1.2, -0.5, 8.0, 0.05);

            assertThat(verdict.growthAxis()).isEqualTo("악화");
            assertThat(verdict.code()).isEqualTo("RECOVERY");
        }
    }

    @Nested
    @DisplayName("COT 극단값 백테스트")
    class CotExtremesTest {

        /** 주간 날짜 n개. */
        private List<LocalDate> weeks(int n) {
            List<LocalDate> dates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                dates.add(LocalDate.of(2020, 1, 7).plusWeeks(i));
            }
            return dates;
        }

        @Test
        @DisplayName("백분위는 '그 시점까지의' 과거로만 계산한다 (미래를 보지 않는다)")
        void noLookAhead() {
            // 앞 구간은 0 근처, 마지막에 큰 값 하나. 전체 표본으로 보면 앞쪽 값들도
            // 상대적으로 낮게 나오지만, 시점별로 보면 앞쪽은 극단이 아닙니다.
            List<LocalDate> dates = weeks(60);
            List<Double> net = new ArrayList<>();
            for (int i = 0; i < 59; i++) {
                net.add(100.0 + (i % 5));
            }
            net.add(10_000.0);

            TreeMap<LocalDate, Double> prices = new TreeMap<>();
            for (int i = 0; i < 80; i++) {
                prices.put(LocalDate.of(2020, 1, 6).plusWeeks(i), 100.0);
            }

            List<CotExtremes.Event> events =
                    CotExtremes.findEvents(dates, net, prices, 20, 95, 5, 4);

            // 마지막 급등만 극단 롱이어야 합니다.
            assertThat(events).isNotEmpty();
            assertThat(events.get(events.size() - 1).side()).isEqualTo("극단 롱");
            assertThat(events.get(events.size() - 1).date()).isEqualTo(dates.get(59));
        }

        @Test
        @DisplayName("아직 N주가 지나지 않은 신호의 수익률은 0이 아니라 null이다")
        void unknownFutureIsNull() {
            TreeMap<LocalDate, Double> prices = new TreeMap<>();
            prices.put(LocalDate.of(2026, 1, 5), 100.0);
            prices.put(LocalDate.of(2026, 1, 12), 110.0);

            assertThat(ForwardReturns.forwardReturn(prices, LocalDate.of(2026, 1, 5), 1))
                    .isEqualTo(10.0, within(1e-9));
            // 13주 뒤 가격이 없습니다.
            assertThat(ForwardReturns.forwardReturn(prices, LocalDate.of(2026, 1, 5), 13)).isNull();
        }

        @Test
        @DisplayName("기준일이 휴장일이면 다음 거래일 종가를 쓴다")
        void usesNextTradingDay() {
            TreeMap<LocalDate, Double> prices = new TreeMap<>();
            prices.put(LocalDate.of(2026, 1, 5), 100.0);    // 월
            prices.put(LocalDate.of(2026, 2, 2), 120.0);

            // 1월 3일(토)에는 가격이 없습니다 → 1월 5일을 씁니다.
            assertThat(ForwardReturns.forwardReturn(prices, LocalDate.of(2026, 1, 3), 4))
                    .isEqualTo(20.0, within(1e-9));
        }

        @Test
        @DisplayName("요약은 표본이 없으면 0이 아니라 null을 돌려준다")
        void emptySummaryIsNull() {
            ForwardReturns.Summary summary = ForwardReturns.summarize(List.of());

            assertThat(summary.count()).isZero();
            assertThat(summary.mean()).isNull();
            assertThat(summary.winRate()).isNull();
        }

        @Test
        @DisplayName("평균·중앙값·승률을 정확히 센다 (null은 세지 않는다)")
        void summaryNumbers() {
            List<Double> returns = new ArrayList<>(List.of(10.0, -5.0, 3.0, 2.0));
            returns.add(null);

            ForwardReturns.Summary summary = ForwardReturns.summarize(returns);

            assertThat(summary.count()).isEqualTo(4);
            assertThat(summary.mean()).isEqualTo(2.5, within(1e-9));
            assertThat(summary.median()).isEqualTo(2.5, within(1e-9));
            assertThat(summary.winRate()).isEqualTo(75.0, within(1e-9));
            assertThat(summary.best()).isEqualTo(10.0);
            assertThat(summary.worst()).isEqualTo(-5.0);
        }
    }
}
