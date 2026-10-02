package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Map;

/**
 * 시계열 공통 계산 (기간 수익률 · 백분위 · 변화량).
 *
 * <p><b>0.0과 "데이터 없음"을 절대 섞지 않습니다.</b> 표본이 부족하면 0이 아니라
 * {@code null}을 돌려주고 화면이 {@code —}로 그립니다. 구버전 섹터 화면은 이럴 때
 * 0.0을 돌려줬는데, "0.00%"는 '데이터 없음'이 아니라 '보합'으로 읽힙니다. 신규
 * 상장 ETF의 1년 수익률이 보합인 것처럼 표시되고 순위 계산에도 섞여 들어갔습니다.
 *
 * <p><b>입력에 null이 없어야 합니다.</b> 여기 있는 함수들은 {@code List<Double>}의
 * 원소가 모두 채워져 있다고 가정합니다. 저장본에서 읽을 때 {@link Json#pointValues}나
 * 호출부에서 미리 걸러 주세요.
 */
public final class SeriesMath {

    private SeriesMath() {
    }

    /**
     * n거래일 전 대비 수익률.
     *
     * @param closes 종가 (오름차순, 마지막이 최근)
     * @param days   며칠 전과 비교할지 (<b>달력이 아니라 거래일 수</b>)
     * @return 수익률(%). 표본이 {@code days}보다 짧거나 과거 가격이 0이면 null
     */
    public static Double periodReturn(List<Double> closes, int days) {
        if (closes == null || closes.size() <= days || days < 0) {
            return null;
        }
        double current = closes.get(closes.size() - 1);
        double past = closes.get(closes.size() - 1 - days);
        if (past == 0.0) {
            return null;
        }
        return (current / past - 1.0) * 100.0;
    }

    /**
     * 연초 대비 수익률 — 해당 연도의 <b>첫 관측</b>과 마지막 값을 비교합니다.
     *
     * @param dates  날짜 (오름차순)
     * @param closes 같은 길이의 종가
     * @param year   기준 연도 (한국 기준 연도를 넘기세요 — {@code Kst.today().getYear()})
     * @return 수익률(%). 길이가 다르거나 그 해 표본이 없거나 첫 값이 0이면 null
     */
    public static Double yearToDateReturn(List<LocalDate> dates, List<Double> closes, int year) {
        if (dates == null || closes == null || dates.size() != closes.size() || closes.isEmpty()) {
            return null;
        }
        Double first = null;
        for (int i = 0; i < dates.size(); i++) {
            if (dates.get(i).getYear() == year) {
                first = closes.get(i);
                break;
            }
        }
        if (first == null || first == 0.0) {
            return null;
        }
        return (closes.get(closes.size() - 1) / first - 1.0) * 100.0;
    }

    /**
     * 표본 안에서 <b>마지막 값</b>의 백분위 — "지금이 역사적으로 어디쯤인가".
     *
     * @param values 비교 대상 표본. 마지막 원소가 판정 대상입니다
     * @return 0~100. 비어 있으면 null
     *
     * <p>마지막 값 자신도 세므로 표본이 1개면 항상 100입니다. 화면은 표본 수를
     * 함께 보여 줘야 이 값을 제대로 읽을 수 있습니다.
     */
    public static Double percentile(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        double last = values.get(values.size() - 1);
        long lessOrEqual = values.stream().filter(v -> v <= last).count();
        return (double) lessOrEqual / values.size() * 100.0;
    }

    /** 마지막 값. 비어 있으면 null. */
    public static Double last(List<Double> values) {
        return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** 끝에서 두 번째 값. 표본이 2개 미만이면 null. */
    public static Double previous(List<Double> values) {
        return values == null || values.size() < 2 ? null : values.get(values.size() - 2);
    }

    /**
     * a − b.
     *
     * @return 둘 중 하나라도 null이면 null (0으로 메우지 않습니다)
     */
    public static Double difference(Double a, Double b) {
        return (a == null || b == null) ? null : a - b;
    }

    /**
     * 날짜 기준 n주 변화: 마지막 날짜의 값 − (마지막 날짜 − n주)의 값.
     *
     * <p>행 개수로 세지 않습니다. 일 단위 시계열에서 "4행 전"은 4거래일 전이지 4주 전이
     * 아닙니다(순유동성 화면이 실제로 그렇게 계산해 국면 화면과 다른 값을 보여 줬습니다).
     * 기준일에 값이 없으면 그보다 앞선 가장 가까운 날의 값을 씁니다(주간 발표 계열 대응).
     *
     * @param series 날짜 → 값
     * @param weeks  몇 주 전과 비교할지
     * @return 변화량. 시계열이 비었거나 n주 전 값이 없으면 null
     */
    public static Double changeOverWeeks(NavigableMap<LocalDate, Double> series, int weeks) {
        return series.isEmpty() ? null : changeOverWeeks(series, series.lastKey(), weeks);
    }

    /** {@link #changeOverWeeks(NavigableMap, int)}를 임의의 기준일에서. */
    public static Double changeOverWeeks(NavigableMap<LocalDate, Double> series, LocalDate asOf, int weeks) {
        return difference(valueAsOf(series, asOf), valueAsOf(series, asOf.minusWeeks(weeks)));
    }

    /**
     * 두 계열의 차(a − b)를 <b>양쪽에 값이 있는 날짜에서만</b> 계산합니다.
     *
     * <p>한쪽 날짜만 있는 날은 버립니다 — 0으로 메우면 없는 스프레드가 생깁니다.
     * 금리차·CP 스프레드·파생 계열이 같은 루프를 세 벌 들고 있었습니다.
     */
    public static NavigableMap<LocalDate, Double> subtractAligned(Map<LocalDate, Double> a,
                                                                  Map<LocalDate, Double> b) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> entry : a.entrySet()) {
            Double other = b.get(entry.getKey());
            if (entry.getValue() != null && other != null) {
                out.put(entry.getKey(), entry.getValue() - other);
            }
        }
        return out;
    }

    /** 그 날짜 또는 그보다 앞선 가장 가까운 날짜의 값. 없으면 null. */
    public static Double valueAsOf(NavigableMap<LocalDate, Double> series, LocalDate date) {
        Map.Entry<LocalDate, Double> entry = series.floorEntry(date);
        return entry == null ? null : entry.getValue();
    }

    /**
     * 변화율.
     *
     * @param current  현재값
     * @param previous 기준값
     * @return 변화율(%). 기준값이 없거나 0이면 null
     */
    public static Double percentChange(Double current, Double previous) {
        if (current == null || previous == null || previous == 0.0) {
            return null;
        }
        return (current - previous) / previous * 100.0;
    }

    /**
     * 구간 최소~최대 사이에서 값의 위치.
     *
     * @param window 비교 구간
     * @param value  위치를 잴 값
     * @return 0(최소)~100(최대). 구간이 비어 있으면 null
     *
     * <p>구간의 최소와 최대가 같으면(값이 전부 같으면) 0으로 나누는 것을 피하려고
     * 분모를 1로 둡니다. 이때 결과는 0이며, 표본이 무의미하다는 뜻입니다.
     */
    public static Double rangePosition(List<Double> window, double value) {
        if (window == null || window.isEmpty()) {
            return null;
        }
        double min = window.stream().mapToDouble(Double::doubleValue).min().orElse(value);
        double max = window.stream().mapToDouble(Double::doubleValue).max().orElse(value);
        double span = (max - min) == 0 ? 1 : (max - min);
        return (value - min) / span * 100.0;
    }

    /**
     * 기간 문자열을 일수로.
     *
     * @param period yfinance 표기 ("1d","5d","1mo","3mo","6mo","1y","2y","5y")
     * @return 달력 일수. 모르는 표기면 null
     */
    public static Integer periodDays(String period) {
        if (period == null) {
            return null;
        }
        return switch (period) {
            case "1d" -> 1;
            case "5d" -> 5;
            case "1mo" -> 31;
            case "3mo" -> 92;
            case "6mo" -> 183;
            case "1y" -> 366;
            case "2y" -> 731;
            case "5y" -> 1827;
            default -> null;
        };
    }

    /**
     * 마지막 날짜 기준으로 최근 n일 구간만 남깁니다.
     *
     * @param dates  날짜 (오름차순)
     * @param values 같은 길이의 값
     * @param days   남길 일수. null이면 자르지 않습니다
     * @return 잘린 목록.
     *
     * <p><b>주의</b> — 남는 표본이 2개 미만이면 <b>원본을 그대로</b> 돌려줍니다.
     * 한 점짜리 차트를 그리느니 넓은 구간을 보여 주는 편이 낫다는 선택입니다.
     * 호출부가 "정확히 n일치"를 기대하면 안 됩니다.
     */
    public static <T> List<T> tailByDays(List<LocalDate> dates, List<T> values, Integer days) {
        if (days == null || dates == null || values == null
                || dates.size() != values.size() || dates.isEmpty()) {
            return values;
        }
        LocalDate cutoff = dates.get(dates.size() - 1).minusDays(days);
        List<T> out = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            if (!dates.get(i).isBefore(cutoff)) {
                out.add(values.get(i));
            }
        }
        return out.size() >= 2 ? out : values;
    }
}
