package com.macrodash.feature.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 번역 단계도 대기 한도 안에 든다(QA-094).
 *
 * <p>AWS 데모는 엔진이 Haiku 하나라 늘 '직접 고른 엔진' 경로를 탑니다. 대기 한도 110초는 앞단 CloudFront(120초)보다 앱이
 * 먼저 포기하려고 고른 값인데(ADR 0014), 응답이 한국어가 아니면 번역 호출이 한도 110초를 처음부터 다시 받아 최악 220초를
 * 기다렸습니다. 여기서는 Claude 호출을 가로채 걸린 시간만큼 시계를 움직이고, 각 호출이 받은 대기 한도를 기록합니다(네트워크 없음).
 */
class AiServiceDeadlineTest {

    private static final String ENGLISH = "## Summary\nLiquidity is tightening while credit spreads stay calm. "
            + "Rates remain elevated and the curve is flat.";
    private static final String KOREAN = "## 요약\n유동성은 줄고 있지만 신용 스프레드는 안정적입니다. 금리는 높고 곡선은 평평합니다.";

    /** Claude 호출을 가로채는 서비스 — 첫 호출(리포트)은 정해 둔 시간만큼 걸리고 영어로, 그다음(번역)은 한국어로 답합니다. */
    private static final class TimedService extends AiService {
        final List<Integer> attempts = new ArrayList<>();
        long now = 1_000_000L;
        private final long reportMillis;

        TimedService(int timeoutSeconds, int autoAttemptSeconds, int autoBudgetSeconds, String engines,
                     long reportMillis) {
            super(timeoutSeconds, autoAttemptSeconds, autoBudgetSeconds, engines);
            this.reportMillis = reportMillis;
            for (String field : List.of("nvidiaKey", "cerebrasKey", "cloudflareAccountId", "cloudflareToken")) {
                ReflectionTestUtils.setField(this, field, "");
            }
            ReflectionTestUtils.setField(this, "anthropicKey", "test-key");
            clockMillis = () -> now;
        }

        @Override
        Map<String, Object> callAnthropic(String label, String model, String prompt, String systemPrompt,
                                          int attemptSeconds) {
            attempts.add(attemptSeconds);
            boolean report = attempts.size() == 1;
            if (report) {
                now += reportMillis;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", true);
            out.put("charged", true);
            out.put("provider", label);
            out.put("model", model);
            out.put("response", report ? ENGLISH : KOREAN);
            out.put("latencyMs", report ? reportMillis : 0L);
            return out;
        }
    }

    @Test
    @DisplayName("QA-094: 직접 고른 엔진(AWS)의 번역은 남은 시간만 받는다 — 110초 중 40초를 썼으면 70초")
    void directTranslationGetsOnlyTheRemainingTime() {
        TimedService ai = new TimedService(110, 60, 180, "anthropic_haiku", 40_000);

        Map<String, Object> result = ai.generate("anthropic_haiku", "질문", "한국어로 답하십시오.");

        assertThat(ai.attempts).containsExactly(110, 70);
        assertThat(result.get("response")).isEqualTo(KOREAN);
    }

    @Test
    @DisplayName("QA-094: 남은 시간이 15초보다 적으면 번역을 건너뛰고 원문과 이유를 준다 — 앞단 120초보다 먼저 끝나게")
    void directTranslationIsSkippedWhenTimeIsUp() {
        TimedService ai = new TimedService(110, 60, 180, "anthropic_haiku", 100_000);

        Map<String, Object> result = ai.generate("anthropic_haiku", "질문", "한국어로 답하십시오.");

        assertThat(ai.attempts).as("번역 호출이 나가지 않음").containsExactly(110);
        assertThat(result.get("status")).isEqualTo(true);
        assertThat(result.get("response")).isEqualTo(ENGLISH);
        assertThat(String.valueOf(result.get("translationInfo"))).contains("번역 건너뜀", "110초", "원문");
    }

    @Test
    @DisplayName("QA-094: 자동 탐색도 예산이 15초 미만으로 남으면 번역하지 않는다 — '전체 N초' 약속을 넘기지 않게")
    void failoverTranslationDoesNotOverrunTheBudget() {
        // 허용 목록 없음 + Claude 키만 → 자동 탐색이 켜지고 첫 엔진(Haiku)이 답합니다. 예산 60초 중 50초를 씀.
        TimedService ai = new TimedService(240, 60, 60, "", 50_000);

        Map<String, Object> result = ai.generate("auto", "질문", "한국어로 답하십시오.");

        assertThat(ai.attempts).as("예전에는 남은 10초 대신 최소 15초를 줘 예산을 넘겼음").containsExactly(60);
        assertThat(result.get("response")).isEqualTo(ENGLISH);
        assertThat(String.valueOf(result.get("translationInfo"))).contains("번역 건너뜀");
    }
}
