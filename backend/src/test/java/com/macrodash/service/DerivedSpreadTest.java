package com.macrodash.service;

import com.macrodash.analytics.AdvancedIndicators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 30Y-3M 금리차는 FRED에 없는 시리즈라 우리가 계산합니다.
 *
 * <p>계산 자체보다 중요한 것은 <b>없는 값을 만들어내지 않는 것</b>입니다.
 * 한쪽 시리즈에만 있는 날짜를 앞뒤 값으로 메우면, 실제로는 발표되지 않은 날의
 * 스프레드가 화면에 생깁니다.
 */
class DerivedSpreadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("30Y-3M은 DGS30 − DGS3MO로 정의돼 있다")
    void derivedDefinition() {
        assertThat(MacroService.DERIVED_SPREADS).containsKey("T30Y3M");
        assertThat(MacroService.DERIVED_SPREADS.get("T30Y3M"))
                .containsExactly("DGS30", "DGS3MO");
    }

    @Test
    @DisplayName("심화 지표 목록과 해석 규칙에 30Y-3M이 들어 있다")
    void advancedCatalogIncludesIt() {
        assertThat(AdvancedIndicators.DISPLAY_ORDER).contains("T30Y3M");
        assertThat(AdvancedIndicators.SERIES).containsKey("T30Y3M");

        // 부호의 의미는 10Y-3M과 같습니다 — 음수면 역전.
        assertThat(AdvancedIndicators.interpret("T30Y3M", -0.2).status()).contains("역전");
        assertThat(AdvancedIndicators.interpret("T30Y3M", 1.5).status()).isEqualTo("정상");
    }

    @Test
    @DisplayName("13F 정정 공시로 같은 분기가 두 번 와도 하나만 남는다")
    void duplicateQuartersAreCollapsed() throws Exception {
        // SEC에는 13F-HR/A(정정)가 있어 같은 reportDate가 두 번 나타납니다.
        // 그대로 두면 히트맵에 빈 열이 하나 더 생깁니다(화면에서 실제로 봤습니다).
        var quarters = java.util.List.of(
                MAPPER.readTree("""
                        {"filingDate":"2026-08-20","reportDate":"2026-06-30","holdings":[]}"""),
                MAPPER.readTree("""
                        {"filingDate":"2026-08-14","reportDate":"2026-06-30","holdings":[]}"""),
                MAPPER.readTree("""
                        {"filingDate":"2026-05-15","reportDate":"2026-03-31","holdings":[]}"""));

        var deduped = Sec13FService.dedupeByReportDate(quarters);

        assertThat(deduped).hasSize(2);
        // 목록은 최신순이므로 먼저 나온 것(더 최근 제출)을 남깁니다.
        assertThat(deduped.get(0).path("filingDate").asString()).isEqualTo("2026-08-20");
        assertThat(deduped.get(1).path("reportDate").asString()).isEqualTo("2026-03-31");
    }
}
