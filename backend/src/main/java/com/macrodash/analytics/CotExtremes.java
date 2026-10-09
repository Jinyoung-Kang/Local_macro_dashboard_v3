package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * COT 극단 포지션 이후 무슨 일이 있었는지 (백테스트).
 *
 * <p>화면은 "3년 표본 백분위 96%"처럼 지금이 역사적 극단이라고 알려 줍니다.
 * 그런데 <b>그래서 어땠는데?</b>에 답이 없었습니다. 여기서 과거에 같은 극단이
 * 나왔던 시점을 모두 찾아, 그 뒤 4주·13주 수익률이 어땠는지 세어 봅니다.
 *
 * <p><b>미래 정보를 쓰지 않습니다(look-ahead 방지).</b> 백분위는 전체 표본이
 * 아니라 <b>그 시점까지의</b> 과거 구간으로만 계산합니다. 전체 표본으로 계산하면
 * "그때는 알 수 없었던 사실"로 과거를 판정하게 되고, 백테스트 결과가 실제보다
 * 좋아 보입니다.
 *
 * <p><b>비교 기준을 함께 돌려줍니다.</b> 극단 이후 평균 +2%가 좋은 숫자인지는
 * 전체 구간 평균과 비교해야 알 수 있습니다. 둘을 나란히 두지 않으면 아무 의미
 * 없는 숫자를 신호로 착각하게 됩니다.
 */
public final class CotExtremes {

    private CotExtremes() {
    }

    /**
     * 기준일(화)에서 진입일까지의 날 수. CFTC는 화요일 기준 포지션을 <b>금요일</b>에 공시합니다.
     * 화요일 종가에서 재면 공시 전 사흘(수·목·금)의 움직임을 신호의 성과로 세게 됩니다(QA-012).
     * 기준일 + 4일(토) 이후 첫 거래일, 즉 공시 다음 거래일 종가에서 출발합니다.
     */
    public static final int RELEASE_ENTRY_DAYS = 4;

    /** 진입·청산일에서 가장 가까운 거래일까지 허용하는 날 수(주말 + 연휴). 넘으면 가격을 모르는 것으로 봅니다. */
    public static final int MAX_GAP_DAYS = 7;

    /** 공시가 나온 뒤 처음 들어갈 수 있는 날(이 날 이후 첫 거래일 종가에서 출발). */
    public static LocalDate entryDate(LocalDate asOf) {
        return asOf.plusDays(RELEASE_ENTRY_DAYS);
    }

    /** 극단 신호 1건과 이후 수익률. */
    public record Event(LocalDate date, double net, double percentile, String side,
                        Map<String, Double> forwardReturns) {
    }

    /** 수익률 묶음의 요약. 표본이 없으면 모든 값이 null입니다. */
    public record Summary(int count, Double mean, Double median, Double winRate,
                          Double best, Double worst) {

        public static Summary empty() {
            return new Summary(0, null, null, null, null, null);
        }
    }

    /**
     * 극단 시점을 찾아 이후 수익률을 붙입니다.
     *
     * @param dates          주간 공시 기준일 (오름차순)
     * @param net            같은 길이의 비상업(투기) 순포지션
     * @param prices         일별 가격 (대용 ETF 종가)
     * @param lookbackWeeks  백분위를 계산할 과거 구간(주). 이 구간을 채우지 못한
     *                       앞부분은 판정하지 않습니다.
     * @param upper          상단 백분위 임계치(예: 95)
     * @param lower          하단 백분위 임계치(예: 5)
     * @param horizonWeeks   이후 몇 주 뒤를 볼지 (예: 4, 13)
     * @return 극단 신호와 각 신호의 이후 수익률. 구간을 채우지 못한 앞부분과
     *         임계치를 넘지 않은 시점은 포함되지 않습니다
     */
    public static List<Event> findEvents(List<LocalDate> dates, List<Double> net,
                                         NavigableMap<LocalDate, Double> prices,
                                         int lookbackWeeks, double upper, double lower,
                                         int... horizonWeeks) {
        List<Event> events = new ArrayList<>();
        if (dates == null || net == null || dates.size() != net.size() || prices == null) {
            return events;
        }

        for (int i = lookbackWeeks; i < dates.size(); i++) {
            Double current = net.get(i);
            if (current == null) {
                continue;
            }
            List<Double> window = new ArrayList<>();
            for (int j = i - lookbackWeeks; j <= i; j++) {
                Double value = net.get(j);
                if (value != null) {
                    window.add(value);
                }
            }
            // 구간이 절반도 안 차면 백분위를 말할 수 없습니다.
            if (window.size() < lookbackWeeks / 2) {
                continue;
            }
            Double percentile = SeriesMath.percentile(window);
            if (percentile == null) {
                continue;
            }

            String side = percentile >= upper ? "극단 롱" : percentile <= lower ? "극단 숏" : null;
            if (side == null) {
                continue;
            }

            Map<String, Double> forward = new LinkedHashMap<>();
            for (int weeks : horizonWeeks) {
                forward.put(weeks + "w", forwardReturn(prices, entryDate(dates.get(i)), weeks));
            }
            events.add(new Event(dates.get(i), current, percentile, side, forward));
        }
        return events;
    }

    /**
     * 기준일로부터 N주 뒤 수익률(%).
     *
     * @param prices 일별 가격 (대용 ETF 종가)
     * @param from   기준일 (COT 공시 기준일)
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

    /**
     * 비교 기준: 모든 주간 시점의 이후 수익률.
     *
     * @param dates  모든 주간 공시 기준일
     * @param prices 일별 가격
     * @param weeks  몇 주 뒤를 볼지
     * @return 같은 방식으로 요약한 "아무 때나 들어갔을 때"의 성적
     *
     * <p>극단 이후 성적만 보여 주면 잘한 것처럼 보입니다. 비교 기준과 나란히
     * 놓아야 신호에 의미가 있는지 알 수 있습니다.
     */
    public static Summary baseline(List<LocalDate> dates, NavigableMap<LocalDate, Double> prices,
                                   int weeks) {
        List<Double> returns = new ArrayList<>();
        if (dates != null) {
            for (LocalDate date : dates) {
                returns.add(forwardReturn(prices, entryDate(date), weeks));
            }
        }
        return summarize(returns);
    }
}
