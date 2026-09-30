package com.macrodash.analytics;

import java.util.Map;

/**
 * 투자자별 매매 기록 하나 (토스증권 공식, 수집기가 정규화한 모양).
 *
 * <p>값을 모르면 {@code null}입니다. 0으로 채우지 않습니다 — 당일 잠정 기록은 개인·기관
 * 세부·기타법인이 비어 있고, 그 "모름"이 계산까지 그대로 전달되어야 합니다.
 *
 * <p>저장본(JSON)에서 이 모양으로 바꾸는 일은 {@code support.FlowJson}이 합니다. 계산
 * 코드가 JSON 라이브러리를 모르게 하려는 분리입니다.
 *
 * @param date                 기준일 (YYYY-MM-DD). 모르면 null
 * @param updatedAt            토스 갱신 시각. 모르면 null
 * @param investors            {@code foreigner|institution|individual|otherCorporation → 금액}.
 *                             모르는 분류는 들어 있지 않습니다
 * @param breakdown            기관 세부({@code pensionFund|financialInvestment|…}) → 금액.
 *                             세부가 없는 기록은 빈 맵
 * @param foreignerHoldingRate 외국인 보유율(0~1). 종목 기록에만 있고, 모르면 null
 */
public record FlowRecord(String date, String updatedAt, Map<String, Amounts> investors,
                         Map<String, Amounts> breakdown, Double foreignerHoldingRate) {

    /** 그룹 이름 — 저장본의 필드 이름과 같습니다. */
    public static final String INVESTORS = "investors";
    public static final String BREAKDOWN = "breakdown";

    /**
     * 매수·매도·순매수. 시장 기록은 원(KRW), 종목 기록은 주(株)입니다.
     * 각 값은 저장본에 정수로 있을 때만 채워지고, 없거나 정수가 아니면 null입니다.
     */
    public record Amounts(Long buy, Long sell, Long net) {
    }

    public FlowRecord {
        investors = investors == null ? Map.of() : Map.copyOf(investors);
        breakdown = breakdown == null ? Map.of() : Map.copyOf(breakdown);
    }

    /**
     * {@code group.key.side}의 값.
     *
     * @param group {@link #INVESTORS} 또는 {@link #BREAKDOWN}
     * @param key   투자자 키 (예: {@code foreigner})
     * @param side  {@code buy|sell|net}
     * @return 모르면 null
     */
    Long value(String group, String key, String side) {
        Map<String, Amounts> byKey = INVESTORS.equals(group) ? investors
                : BREAKDOWN.equals(group) ? breakdown : Map.of();
        Amounts amounts = byKey.get(key);
        if (amounts == null) {
            return null;
        }
        return switch (side) {
            case "buy" -> amounts.buy();
            case "sell" -> amounts.sell();
            case "net" -> amounts.net();
            default -> null;
        };
    }
}
