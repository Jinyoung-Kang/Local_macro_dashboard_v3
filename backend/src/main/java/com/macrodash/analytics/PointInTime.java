package com.macrodash.analytics;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 과거의 그날 <b>알 수 있었던</b> 값 — 발표 지연을 반영한 시점 기준 시계열.
 *
 * <p>시계열은 관측일로 저장됩니다. 그런데 NFCI는 금요일 기준 값이 다음 주 수요일에 나오고, 일별 FRED 값도
 * 다음 영업일에 올라옵니다. 관측일로 과거를 재현하면 그때는 아직 발표되지 않은 값으로 판정하게 되고,
 * 성과가 실제보다 좋아 보입니다(COT 백테스트의 QA-012와 같은 종류의 오류).
 *
 * <p>규칙은 늦게 알았다고 보는 쪽(보수적)으로 잡습니다. 미국 공휴일은 반영하지 않습니다 — 공휴일 다음 날
 * 발표되는 값을 하루 일찍 안 것으로 볼 수 있는 한계가 있습니다.
 */
public final class PointInTime {

    private PointInTime() {
    }

    /** 관측일 → 쓸 수 있게 되는 날의 규칙. */
    public enum Release {
        /** 일별 FRED(T10Y3M·하이일드)·순유동성: 다음 영업일(주말 건너뜀). */
        NEXT_BUSINESS_DAY,
        /** NFCI: 금요일 기준 → 다음 주 수요일 발표. */
        NEXT_WEDNESDAY
    }

    public static LocalDate availableOn(LocalDate observed, Release release) {
        return switch (release) {
            case NEXT_BUSINESS_DAY -> {
                LocalDate next = observed.plusDays(1);
                while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    next = next.plusDays(1);
                }
                yield next;
            }
            case NEXT_WEDNESDAY -> observed.with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
        };
    }

    /**
     * 관측일 기준 시계열을 "알게 된 날" 기준으로 다시 놓습니다. 같은 날 알게 된 값이 여럿이면 가장 늦은 관측값이
     * 남습니다. 결과에 {@code floorEntry(날짜)}를 하면 그날까지 발표된 가장 최근 값이 나옵니다.
     */
    public static NavigableMap<LocalDate, Double> asKnownOn(NavigableMap<LocalDate, Double> observed,
                                                           Release release) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        if (observed == null) {
            return out;
        }
        for (Map.Entry<LocalDate, Double> entry : observed.entrySet()) {
            if (entry.getValue() != null) {
                out.put(availableOn(entry.getKey(), release), entry.getValue());
            }
        }
        return out;
    }
}
