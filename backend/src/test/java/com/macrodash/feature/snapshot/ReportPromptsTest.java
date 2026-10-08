package com.macrodash.feature.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리포트 프롬프트의 계약 — 화면({@code frontend/src/lib/markdown.ts}·리포트 화면)이 기대하는 형식과 원칙.
 */
class ReportPromptsTest {

    private static final Pattern SECTION = Pattern.compile("(?m)^## (\\d+)\\. (.+)$");

    private static Map<String, String> prompts() {
        return new SnapshotTextService(null, null, null, null, null, null, null, null).reportPrompts();
    }

    @Test
    @DisplayName("세 리포트가 같은 공통 규칙으로 시작하고 순서가 화면과 같다")
    void allReportsShareCommonRules() {
        Map<String, String> prompts = prompts();
        assertThat(prompts.keySet()).containsExactly("종합 매크로 브리핑", "국내 파생·수급 분석", "13F 스마트머니 테마");
        assertThat(prompts.values()).allSatisfy(prompt -> {
            assertThat(prompt).startsWith(SnapshotTextService.REPORT_COMMON_RULES);
            assertThat(prompt).contains("[역할]", "[절 구성]");
        });
    }

    @Test
    @DisplayName("절은 '## 1. 핵심 요약'으로 시작해 번호가 1부터 빠짐없이 이어지고, 데이터 신뢰도 절이 있다")
    void sectionsAreNumberedAndStartWithSummary() {
        prompts().forEach((type, prompt) -> {
            Matcher m = SECTION.matcher(prompt);
            int expected = 1;
            List<String> titles = new ArrayList<>();
            while (m.find()) {
                assertThat(Integer.parseInt(m.group(1))).as(type + " 절 번호").isEqualTo(expected++);
                titles.add(m.group(2).trim());
            }
            assertThat(titles).as(type).hasSizeGreaterThanOrEqualTo(5);
            assertThat(titles.get(0)).as(type).isEqualTo("핵심 요약");
            assertThat(titles.get(1)).as(type).isEqualTo("데이터 신뢰도");
        });
    }

    @Test
    @DisplayName("투자 권유를 지시하지 않는다 — 예전 '실전 포트폴리오 행동 지침' 절은 없다")
    void noTradingInstructions() {
        assertThat(SnapshotTextService.REPORT_COMMON_RULES).contains("매수·매도·비중을 지시하지 않습니다");
        assertThat(prompts().values()).noneMatch(prompt -> prompt.contains("행동 지침"));
    }

    @Test
    @DisplayName("단위 환산과 출처가 다른 두 값에 대한 규칙이 있다 — 순유동성·스프레드 오독 재발 방지")
    void guardsAgainstKnownMisreadings() {
        assertThat(SnapshotTextService.REPORT_COMMON_RULES)
                .contains("1조 = 10,000억")
                .contains("\"불일치\"로 단정하지 말고");
    }
}
