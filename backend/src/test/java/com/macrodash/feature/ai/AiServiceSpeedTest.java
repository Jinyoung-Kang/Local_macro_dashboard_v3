package com.macrodash.feature.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 탐색이 "빨리 답하거나 빨리 포기하는지" 확인합니다.
 *
 * <p>화면에서 리포트 한 장에 10분 넘게 걸렸습니다. 원인은 두 가지였습니다.
 * <ul>
 *   <li>폴오버 순서 맨 앞이 추론형 대형 모델이었습니다(호출 한 번에 수 분).</li>
 *   <li>시도마다 전체 한도(기본 240초)를 그대로 써서, 실패가 쌓이면
 *       "엔진 수 × 240초"까지 기다릴 수 있었습니다.</li>
 * </ul>
 *
 * <p>실제 호출은 외부 제공자로 나가므로 여기서는 <b>순서와 한도</b>만 봅니다.
 * 응답 본문 처리는 {@link AiServiceContentTypeTest}가 로컬 서버로 확인합니다.
 */
class AiServiceSpeedTest {

    /** 추론 단계를 거치느라 한 번 호출에 수 분이 걸리는 엔진들. */
    private static final List<String> REASONING_ENGINES = List.of(
            "nvidia_nemotron", "nvidia_gpt_oss_120b", "cloudflare_deepseek");

    @Test
    @DisplayName("자동 탐색은 빠른 엔진부터 부른다")
    void failoverTriesFastEnginesFirst() {
        assertThat(AiService.FAILOVER_ORDER.get(0))
                .as("첫 시도는 가장 빠른 엔진이어야 합니다")
                .isEqualTo("cerebras_llama");

        int firstReasoning = AiService.FAILOVER_ORDER.stream()
                .filter(REASONING_ENGINES::contains)
                .findFirst()
                .map(AiService.FAILOVER_ORDER::indexOf)
                .orElseThrow();

        assertThat(firstReasoning)
                .as("추론형 모델은 빠른 엔진들이 모두 실패한 뒤에만 시도해야 합니다")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("폴오버 목록의 엔진은 모두 실제 등록된 엔진이다")
    void failoverOrderReferencesRealEngines() {
        // 오타가 나면 그 엔진은 "키 없음"으로 조용히 건너뛰어집니다.
        List<String> known = AiService.ENGINES.stream().map(AiService.Engine::id).toList();
        assertThat(known).containsAll(AiService.FAILOVER_ORDER);
    }

    @Test
    @DisplayName("자동 탐색은 시도당 한도와 전체 예산을 함께 갖는다")
    void autoModeHasAttemptLimitAndBudget() {
        AiService service = new AiService(240, 60, 180);

        assertThat(service.autoAttemptSeconds()).isEqualTo(60);
        assertThat(service.autoBudgetSeconds()).isEqualTo(180);
        // 엔진을 직접 고르면 예전처럼 끝까지 기다립니다.
        assertThat(service.timeoutSeconds()).isEqualTo(240);
    }

    @Test
    @DisplayName("시도 한도는 전체 한도를 넘지 않고, 예산은 최소 한 번의 시도를 보장한다")
    void limitsAreClamped() {
        // 전체 한도보다 긴 시도 한도는 의미가 없습니다.
        assertThat(new AiService(45, 300, 600).autoAttemptSeconds()).isEqualTo(45);
        // 너무 짧은 시도 한도는 멀쩡한 엔진도 끊습니다.
        assertThat(new AiService(240, 1, 180).autoAttemptSeconds()).isEqualTo(20);
        // 예산이 시도 한도보다 짧으면 아무 엔진도 부르지 못합니다.
        assertThat(new AiService(240, 60, 10).autoBudgetSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("예산이 남아 있는 만큼만 다음 엔진에 주고, 모자라면 시도하지 않는다")
    void attemptBudgetShrinksAndThenStops() {
        AiService service = new AiService(240, 60, 180);
        long now = 1_000_000L;

        // 예산이 넉넉하면 시도 한도를 그대로 씁니다.
        assertThat(service.attemptBudget(now + 180_000, now)).isEqualTo(60);
        // 남은 시간이 시도 한도보다 짧으면 남은 만큼만 기다립니다.
        assertThat(service.attemptBudget(now + 30_000, now)).isEqualTo(30);
        // 몇 초 남기고 부르면 타임아웃만 한 번 더 겪습니다 — 그만둡니다.
        assertThat(service.attemptBudget(now + 5_000, now)).isEqualTo(-1);
        assertThat(service.attemptBudget(now - 1_000, now)).isEqualTo(-1);
    }

    @Test
    @DisplayName("타임아웃 문구는 '실제로 기다린 한도'를 말한다")
    void timeoutMessageReportsTheLimitActuallyUsed() {
        // 자동 탐색은 60초에서 끊는데 "240초 안에 응답하지 않았습니다"라고 하면
        // 읽는 사람이 설정을 잘못 의심합니다.
        AiService service = new AiService(240, 60, 180);

        assertThat(service.describe(new RuntimeException("Read timed out"), 60))
                .contains("60초")
                .doesNotContain("240초");
    }

    @Test
    @DisplayName("엔진 목록은 예상 속도와 대기 한도를 함께 알려 준다")
    void enginesExposeSpeedHintsAndLimits() {
        AiService service = new AiService(240, 60, 180);
        // @Value로 주입되는 키 필드는 단위 테스트에서 null이라 비워 둡니다.
        for (String field : List.of("nvidiaKey", "cerebrasKey",
                "cloudflareAccountId", "cloudflareToken")) {
            ReflectionTestUtils.setField(service, field, "");
        }

        Map<String, Object> out = service.engines();

        assertThat(out.get("autoAttemptSeconds")).isEqualTo(60);
        assertThat(out.get("autoBudgetSeconds")).isEqualTo(180);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> engines = (List<Map<String, Object>>) out.get("engines");
        assertThat(engines).allSatisfy(engine ->
                assertThat(engine.get("speedHint")).as("%s", engine.get("id")).isNotNull());

        Map<String, Object> reasoning = engines.stream()
                .filter(engine -> "nvidia_nemotron".equals(engine.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(reasoning.get("speedHint"))).contains("느림");
    }
}
