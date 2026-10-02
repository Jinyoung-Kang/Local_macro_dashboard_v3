package com.macrodash.analytics;

import java.util.List;

/** 베타·추적오차의 기준 ETF 티커. 구루 위험과 스코어카드가 함께 씁니다. */
public final class Benchmarks {

    public static final List<String> TICKERS = List.of("SPY", "QQQ", "ACWI");
    public static final String DEFAULT = "SPY";

    private Benchmarks() {
    }

    /** 목록에 없는 요청은 기본값(SPY)으로. */
    public static String resolve(String requested) {
        return TICKERS.contains(requested) ? requested : DEFAULT;
    }
}
