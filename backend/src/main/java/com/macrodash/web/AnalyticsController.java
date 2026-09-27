package com.macrodash.web;

import com.macrodash.service.AnalyticsService;
import com.macrodash.service.ScorecardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 저장된 여러 데이터를 엮어 계산하는 분석 — 🔗 지표 상관관계 · 🧭 시장 국면 · 🩺 종목 스코어카드.
 *
 * <pre>
 *  GET /api/analytics/*   상관 분석 계열·상관계수·국면
 *  GET /api/stock/*       스코어카드 종목·스코어카드
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final ScorecardService scorecard;

    public AnalyticsController(AnalyticsService analytics, ScorecardService scorecard) {
        this.analytics = analytics;
        this.scorecard = scorecard;
    }

    // ----------------------------------------------- 🔗 상관관계 · 🧭 국면
    /** 상관 분석에 쓸 수 있는 계열 목록. */
    @GetMapping("/analytics/series")
    public Map<String, Object> analyticsSeries() {
        return Map.of("series", analytics.catalog());
    }

    /**
     * 두 계열의 상관관계.
     *
     * @param mode change(기본, 변화끼리) 또는 level(수준끼리 — 허위 상관 주의)
     */
    @GetMapping("/analytics/correlation")
    public Map<String, Object> correlation(
            @RequestParam String x,
            @RequestParam String y,
            @RequestParam(defaultValue = "60") int window,
            @RequestParam(defaultValue = "3") int years,
            @RequestParam(defaultValue = "change") String mode) {
        return analytics.correlation(x, y, window, years, mode);
    }

    /** 성장·신용 축 × 유동성 축으로 판정한 시장 국면. */
    @GetMapping("/analytics/regime")
    public Map<String, Object> regime(@RequestParam(defaultValue = "5") int years) {
        return analytics.regime(years);
    }

    // ----------------------------------------------------- 🩺 스코어카드
    /** 스코어카드를 낼 수 있는 종목 목록. */
    @GetMapping("/stock/universe")
    public Map<String, Object> stockUniverse() {
        return scorecard.universe();
    }

    /**
     * 종목 스코어카드 — <b>가격으로 잴 수 있는 것만</b>.
     *
     * <p>재무·성장·밸류에이션은 수집하지 않아 빠져 있습니다. 응답의
     * {@code missing}과 {@code caveat}이 그 사실을 함께 전달합니다.
     */
    @GetMapping("/stock/scorecard")
    public Map<String, Object> stockScorecard(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "SPY") String benchmark,
            @RequestParam(defaultValue = "1") int years) {
        return scorecard.scorecard(symbol, benchmark, years);
    }
}
