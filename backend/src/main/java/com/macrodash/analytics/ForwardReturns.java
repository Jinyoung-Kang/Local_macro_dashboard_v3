package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;

/**
 * 신호 이후 N주 수익률과 그 요약 — COT 백테스트와 국면 판정 성과가 함께 씁니다.
 *
 * <p>두 백테스트가 같은 규칙으로 수익률을 재야 화면의 숫자를 서로 비교할 수 있습니다. 진입일을 언제로 볼지
 * (공시 다음 거래일 등)는 부르는 쪽이 정하고, 여기서는 "그 날 이후 첫 거래일 종가 → N주 뒤 첫 거래일 종가"만 잽니다.
 */
public final class ForwardReturns {

    private ForwardReturns() {
    }

    /** 진입·청산일에서 가장 가까운 거래일까지 허용하는 날 수(주말 + 연휴). 넘으면 가격을 모르는 것으로 봅니다. */
    public static final int MAX_GAP_DAYS = 7;

    /** 수익률 묶음의 요약. 표본이 없으면 모든 값이 null입니다. */
    public record Summary(int count, Double mean, Double median, Double winRate,
                          Double best, Double worst) {

        public static Summary empty() {
            return new Summary(0, null, null, null, null, null);
        }
    }

    /**
     * 기준일로부터 N주 뒤 수익률(%).
     *
     * @param prices 일별 가격 (대용 ETF 종가)
     * @param from   진입일 — 이 날 이후 첫 거래일 종가에서 출발 (COT는 공시 다음 날)
     * @param weeks  몇 주 뒤를 볼지
     * @return 수익률(%). 아직 N주가 지나지 않은 최근 신호는 null입니다 —
     *         없는 미래를 0으로 채우지 않습니다
     *
     * <p>공시 기준일이 휴장일일 수 있으므로 <b>그 날 이후 첫 거래일</b>의 종가를
     * 씁니다.
     */
    public static Double forwardReturn(NavigableMap<LocalDate, Double> prices,
                                       LocalDate from, int weeks) {
        if (prices == null || prices.isEmpty() || from == null) {
            return null;
        }
        LocalDate target = from.plusWeeks(weeks);
        var start = prices.ceilingEntry(from);
        var end = prices.ceilingEntry(target);
        if (start == null || end == null || start.getValue() == null || end.getValue() == null) {
            return null;
        }
        // 가장 가까운 거래일이 일주일 넘게 떨어져 있으면 그 날의 가격을 모르는 것입니다.
        // 가격 이력이 시작되기 전의 신호를 첫 가격일로 당기면, 다른 날 출발한 수익률을
        // 그 신호의 성과로 세게 됩니다(QA-013).
        if (start.getKey().isAfter(from.plusDays(MAX_GAP_DAYS))
                || end.getKey().isAfter(target.plusDays(MAX_GAP_DAYS))) {
            return null;
        }
        if (start.getValue() == 0.0 || !start.getKey().isBefore(end.getKey())) {
            return null;
        }
        return (end.getValue() / start.getValue() - 1.0) * 100.0;
    }

    /**
     * 수익률 묶음 요약.
     *
     * @param returns 수익률 목록 (null·무한대는 걸러집니다)
     * @return 표본 수·평균·중앙값·승률(%)·최고·최저. 쓸 값이 없으면 모든 항목 null
     */
    public static Summary summarize(List<Double> returns) {
        List<Double> values = new ArrayList<>();
        if (returns != null) {
            returns.stream()
                    .filter(value -> value != null && Double.isFinite(value))
                    .forEach(values::add);
        }
        if (values.isEmpty()) {
            return Summary.empty();
        }
        values.sort(Comparator.naturalOrder());

        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / values.size();
        int middle = values.size() / 2;
        double median = values.size() % 2 == 1
                ? values.get(middle)
                : (values.get(middle - 1) + values.get(middle)) / 2.0;
        long wins = values.stream().filter(value -> value > 0).count();

        return new Summary(values.size(), mean, median,
                (double) wins / values.size() * 100.0,
                values.get(values.size() - 1), values.get(0));
    }
}
