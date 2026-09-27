package com.macrodash.store;

/**
 * 데이터셋 이름과 신선도 기준의 단일 출처 (Java 쪽).
 *
 * <p><b>중요</b> — 이 파일은 수집기의 {@code collector/app/catalog.py}와 <b>같은 문자열</b>을
 * 정의해야 합니다. 한쪽이 오타를 내면 "수집은 되는데 화면에는 안 보이는" 버그가
 * 생기고, 그 증상만으로는 원인을 찾기 어렵습니다. 두 파일이 어긋나지 않는지는
 * {@code DatasetsParityTest}가 실제 catalog.py를 읽어 대조합니다.
 */
public final class Datasets {

    private Datasets() {
    }

    // ---------------------------------------------------------------- 스냅샷
    public static final String SNAP_MACRO_COLLECTED = "macro.collected";
    public static final String SNAP_SCRAPER_MARKETS = "macro.scraper_markets";
    public static final String SNAP_FED_LIQUIDITY = "liquidity.fed_net";
    public static final String SNAP_KRX_FUTURES = "krx.futures_history";
    public static final String SNAP_SECTOR_HISTORY = "sector.etf_history";
    public static final String SNAP_FX_HISTORY = "macro.fx_history";
    public static final String SNAP_EQUITY_HISTORY = "equity.price_history";
    public static final String SNAP_COT_HISTORY = "cot.multi_asset";
    /** 한국 공휴일 (천문연 특일정보). 거래소 휴장 판정에 씁니다. */
    public static final String SNAP_KR_HOLIDAYS = "calendar.kr_holidays";
    /** DART 사업보고서 주요계정 (수급 레이더 종목). */
    public static final String SNAP_DART_FUNDAMENTALS = "kr.dart_fundamentals";
    /** 금융위 시세 최신 기준일·시장 합계. 종목별 값은 {@link #OBS_FSC_PRICE}. */
    public static final String SNAP_FSC_PRICES_META = "kr.fsc_prices_meta";
    /** 코스피·코스닥 투자자별 매매대금 (토스증권 공식, 일별). */
    public static final String SNAP_TOSS_MARKET_FLOWS = "kr.toss_market_flows";
    /** 거래대금 상위 100종목의 투자자 매매 (토스증권 공식) — 레이더 폴백용. */
    public static final String SNAP_TOSS_RADAR_UNIVERSE = "kr.toss_radar_universe";

    /** 변동성 지수는 가장 긴 기간으로 한 번 저장하고 짧은 기간은 잘라 씁니다. */
    public static final String VOLATILITY_STORE_PERIOD = "5y";

    /** 수집기가 저장하는 최대 분기 수. q1은 q8의 앞부분입니다. */
    public static final int MAX_TRACKED_QUARTERS = 8;

    /** Daum 선물 수급 조회 기간 (수집기와 동일해야 합니다). */
    public static final int DAUM_TREND_LOOKBACK = 25;

    public static String daumFuturesTrend(int lookbackDays) {
        // .CONTRACT 접미사는 "계약수 기준"임을 명시합니다.
        // (금액(억원) 모드는 Daum이 제공하지 않아 제거됐습니다.)
        return "krx.daum_futures_trend.d" + lookbackDays + ".CONTRACT";
    }

    public static String tickerHistory(String symbol, String period) {
        String safe = symbol.replace("^", "").replace("=", "_").replace(".", "_");
        return "ticker." + safe + "." + period;
    }

    public static String cotContract(String contractCode, int limit) {
        return "cot.contract." + contractCode + ".l" + limit;
    }

    public static String sec13f(String cik, int maxQuarters) {
        return "sec.13f." + cik + ".q" + maxQuarters;
    }

    public static String fredSeries(String seriesId) {
        return "fred.series." + seriesId;
    }

    public static String radarScanner(String market, String investor,
                                      String tradeType, String intervalType) {
        return "radar.scanner." + market + "." + investor + "." + tradeType + "." + intervalType;
    }

    // ------------------------------------------------------------- 누적 이력
    public static final String TS_FRED = "fred";
    public static final String TS_KRX_FUTURES = "krx_futures";
    public static final String TS_LIQUIDITY = "fed_liquidity";
    public static final String OBS_RADAR = "radar_ranking";
    /** 금융위 공식 일별 시세 (entity = 6자리 종목코드). */
    public static final String OBS_FSC_PRICE = "fsc_price";
    /** 시장별 합계 시가총액·거래대금 (series = "KOSPI.marketCap" 등). */
    public static final String TS_FSC_MARKET = "fsc_market";
    /** 종목별 일별 투자자 매매동향 (토스증권 공식, entity = 종목코드). */
    public static final String OBS_TOSS_STOCK_FLOW = "toss_stock_flow";

    // ------------------------------------------------------------- 신선도(초)
    /** 장중 시세성 데이터 (수집 주기 5분의 3배). */
    public static final long MAX_AGE_REALTIME = 15 * 60L;

    /**
     * 매크로 화면이 "자동 갱신"을 켜고 보고 있을 때의 신선도 기준.
     *
     * <p><b>왜 60초가 하한인가</b> — 매크로 카드 21개는 전부 Yahoo를 폴링해
     * 받아옵니다(한 번에 약 3초). 화면이 10초마다 다시 읽는다고 수집까지
     * 10초마다 하면 시간당 요청이 252건에서 7,560건으로 뜁니다. 이 저장소는
     * 이미 <b>Yahoo 429로 스크래핑이 막혀 화면이 빈 적</b>이 있습니다.
     *
     * <p>그렇다고 15분 기준을 그대로 쓰면, 사용자가 10초를 골라도 저장본이
     * 15분 동안 그대로라 <b>같은 숫자만 다시 그립니다</b>. 그래서 보고 있는
     * 동안만 기준을 60초로 낮춥니다. 값은 최대 1분마다 움직이고, 외부 요청은
     * 시간당 1,260건으로 5배에 그칩니다.
     *
     * <p>더 짧게 낮추지 않는 이유가 하나 더 있습니다. 국채·VIX는 출처부터가
     * 15분 지연 시세라, 초 단위로 받아도 같은 값입니다.
     */
    public static final long MAX_AGE_LIVE = 60L;
    /** 일별 확정치 (FRED/KRX 마감). */
    public static final long MAX_AGE_DAILY = 6 * 60 * 60L;
    /** 분기 공시(13F) 등 거의 변하지 않는 데이터. */
    public static final long MAX_AGE_SLOW = 24 * 60 * 60L;
    /** 주 단위로 바뀌는 데이터 (공휴일 발표·분기 공시 기반 재무). */
    public static final long MAX_AGE_WEEKLY = 7 * 24 * 60 * 60L;

    /**
     * 수동 새로고침이라도 이 간격 안에는 다시 받지 않습니다.
     *
     * <p><b>왜 필요한가</b> — 수동 새로고침은 "이 시각 이전 저장본은 낡은
     * 것으로 본다"는 기준을 세웁니다. 그런데 그 기준이 데이터셋 종류를
     * 가리지 않아서, 버튼 한 번에 <b>분기마다 바뀌는 13F까지</b> 다시
     * 받았습니다. 실제 로그에서 5분 사이에 SEC 전수 수집이 세 번 돌았습니다.
     *
     * <pre>
     * 07:20:33 sec_13f 51.80s   ← 스케줄(weekly)
     * 07:21:34 POST /refresh → 07:22:01 sec_13f 31.77s
     * 07:24:33 POST /refresh → 07:24:44 sec_13f 32.05s
     * </pre>
     *
     * SEC는 호출 한도를 명시하고 초과하면 차단합니다. 3분 전에 받은
     * 분기 공시를 다시 받을 이유는 없습니다 — 새로 나온 것이 없습니다.
     *
     * <p>빠르게 바뀌는 데이터는 거의 제한하지 않습니다(1분). 새로고침을
     * 누르는 이유가 대개 그쪽이기 때문입니다.
     */
    public static long minRefetchSeconds(long maxAgeSeconds) {
        if (maxAgeSeconds <= MAX_AGE_REALTIME) {
            return 60L;                 // 시세성: 1분
        }
        if (maxAgeSeconds <= MAX_AGE_DAILY) {
            return 30 * 60L;            // 일별 확정치: 30분
        }
        return 6 * 60 * 60L;            // 분기 공시 등: 6시간
    }
}
