package com.macrodash.feature.ai;

import com.macrodash.feature.auth.DemoViewer;
import com.macrodash.feature.auth.UsageService;
import com.macrodash.feature.snapshot.SnapshotTextService;
import com.macrodash.store.UsageStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 리포트 요청 흐름 고정: 리포트 종류 고르기 → 원본 텍스트로 프롬프트 조립 → 엔진 호출.
 *
 * <p>프롬프트 문구와 응답 필드(reportType·promptChars)는 화면과 AI 결과에 그대로 드러나므로
 * 구조를 바꿀 때 달라지면 안 됩니다.
 */
class AiReportFlowTest {

    private final AiService ai = mock(AiService.class);
    private final SnapshotTextService snapshotText = mock(SnapshotTextService.class);

    AiReportFlowTest() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("종합 매크로 브리핑", "SYSTEM-A");
        prompts.put("리스크 점검", "SYSTEM-B");
        when(snapshotText.reportPrompts()).thenReturn(prompts);
        when(snapshotText.fullText()).thenReturn("DATA");
        when(ai.generate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("text", "결과", "engine", "cerebras_llama"));
    }

    @Test
    @DisplayName("고른 리포트 종류의 시스템 프롬프트로, 원본 텍스트와 요청·추가 지시를 붙여 부른다")
    void assemblesPromptForChosenType() {
        Map<String, Object> out = controller().report(new AiController.ReportRequest("cerebras_llama", "리스크 점검", "짧게"), new org.springframework.mock.web.MockHttpServletRequest());

        String expectedPrompt = "아래는 대시보드가 수집한 최신 원본 데이터입니다.\n\n<data>\nDATA\n</data>"
                + "\n\n요청: 리스크 점검을(를) 작성하십시오. 시스템 지시의 [절 구성]과 [형식]을 따르십시오."
                + "\n추가 지시: 짧게";
        verify(ai).generate("cerebras_llama", expectedPrompt, "SYSTEM-B");
        assertThat(out).containsEntry("text", "결과").containsEntry("engine", "cerebras_llama")
                .containsEntry("reportType", "리스크 점검")
                .containsEntry("promptChars", expectedPrompt.length());
    }

    @Test
    @DisplayName("모르는·빈 리포트 종류는 첫 번째 종류로, 빈 추가 지시는 붙이지 않는다")
    void unknownTypeFallsBackToFirst() {
        Map<String, Object> out = controller().report(new AiController.ReportRequest("auto", "없는 종류", " "), new org.springframework.mock.web.MockHttpServletRequest());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).generate(eq("auto"), prompt.capture(), eq("SYSTEM-A"));
        assertThat(prompt.getValue()).endsWith(
                "요청: 종합 매크로 브리핑을(를) 작성하십시오. 시스템 지시의 [절 구성]과 [형식]을 따르십시오.");
        assertThat(out).containsEntry("reportType", "종합 매크로 브리핑");
    }

    @Test
    @DisplayName("추가 지시가 2,000자를 넘으면 엔진을 부르지 않고 400")
    void overlongInstructionIsRejected() {
        String longText = "가".repeat(2001);

        assertThatThrownBy(() -> controller().report(new AiController.ReportRequest("auto", null, longText), new org.springframework.mock.web.MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("2000자 이하");
        verify(ai, never()).generate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("리포트 종류 목록")
    void reportTypes() {
        assertThat(controller().reportTypes().get("types").toString()).isEqualTo("[종합 매크로 브리핑, 리스크 점검]");
    }

    private AiController controller() {
        return new AiController(ai, new AiReportService(ai, snapshotText,
                org.mockito.Mockito.mock(com.macrodash.feature.auth.UsageService.class)));
    }

    /** 데모 열람(ADR 0013) 요청 — 세션 필터가 넣는 사용자 속성을 흉내 냅니다. */
    private static org.springframework.mock.web.MockHttpServletRequest demoRequest() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.macrodash.support.CurrentUser.ATTRIBUTE, com.macrodash.feature.auth.DemoViewer.user());
        return request;
    }

    /** 실제 사용량 서비스(데모 공용 한도 = demoLimit) — 저장소는 흉내이고, 데모는 저장소를 쓰지 않아야 합니다. */
    private AiController controllerWithDemoLimit(com.macrodash.store.UsageStore store, int demoLimit) {
        return new AiController(ai, new AiReportService(ai, snapshotText,
                new com.macrodash.feature.auth.UsageService(store, 20, demoLimit)));
    }

    @Test
    @DisplayName("데모 열람: 공용 하루 한도 안에서 리포트를 만들고, 다 쓰면 엔진을 부르지 않고 계정 한도와 같은 모양으로 알린다")
    void demoSharedQuotaExhaustion() {
        com.macrodash.store.UsageStore store = mock(com.macrodash.store.UsageStore.class);
        when(ai.generate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("status", true, "response", "결과"));
        AiController controller = controllerWithDemoLimit(store, 1);

        Map<String, Object> first = controller.report(new AiController.ReportRequest("anthropic_haiku", null, null), demoRequest());
        assertThat(first).containsEntry("status", true)
                .containsEntry("quota", Map.of("used", 1, "limit", 1, "shared", true));

        Map<String, Object> second = controller.report(new AiController.ReportRequest("anthropic_haiku", null, null), demoRequest());
        assertThat(second.keySet()).containsExactly("status", "error", "quota");
        assertThat(second).containsEntry("status", false)
                .containsEntry("error", "데모의 오늘 AI 리포트 한도(1건)를 모두 썼습니다. 내일(KST 0시) 다시 시도해 주세요.")
                .containsEntry("quota", Map.of("used", 1, "limit", 1, "shared", true));
        verify(ai, org.mockito.Mockito.times(1)).generate(anyString(), anyString(), anyString());
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    @DisplayName("데모 열람: 엔진이 실패하면 공용 한도를 되돌린다(계정 사용자와 같은 규칙)")
    void demoFailureIsRefunded() {
        com.macrodash.store.UsageStore store = mock(com.macrodash.store.UsageStore.class);
        when(ai.generate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("status", false, "error", "API Key 누락"));
        AiController controller = controllerWithDemoLimit(store, 1);

        for (int i = 0; i < 3; i++) {
            Map<String, Object> out = controller.report(new AiController.ReportRequest("anthropic_haiku", null, null), demoRequest());
            assertThat(out).as("시도 %d", i).containsEntry("status", false).containsEntry("error", "API Key 누락")
                    .containsEntry("quota", Map.of("used", 0, "limit", 1, "shared", true));
        }
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    @DisplayName("QA-021 모델이 응답한 뒤의 실패(과금됨)는 한도를 되돌리지 않는다 — 거절을 유도해 상한 없이 부르지 못하게")
    void chargedFailureIsNotRefunded() {
        com.macrodash.store.UsageStore store = mock(com.macrodash.store.UsageStore.class);
        when(ai.generate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("status", false, "error", "Claude가 이 요청을 거절했습니다(refusal)", "charged", true));
        AiController controller = controllerWithDemoLimit(store, 2);

        Map<String, Object> out = controller.report(new AiController.ReportRequest("anthropic_haiku", null, null), demoRequest());

        assertThat(out).containsEntry("status", false)
                .containsEntry("quota", Map.of("used", 1, "limit", 2, "shared", true));
    }

    @Test
    @DisplayName("QA-021 한도를 쓴 뒤 원본 텍스트를 만들다 예외가 나면 엔진을 부르지 않았으므로 되돌린다")
    void exceptionBeforeCallingEngineIsRefunded() {
        com.macrodash.store.UsageStore store = mock(com.macrodash.store.UsageStore.class);
        when(snapshotText.fullText()).thenThrow(new IllegalStateException("DB 일시 장애"));
        com.macrodash.feature.auth.UsageService usage = new com.macrodash.feature.auth.UsageService(store, 20, 1);
        AiController controller = new AiController(ai, new AiReportService(ai, snapshotText, usage));

        assertThatThrownBy(() -> controller.report(new AiController.ReportRequest("anthropic_haiku", null, null),
                demoRequest())).isInstanceOf(IllegalStateException.class);
        assertThat(usage.demoQuota().used()).as("되돌림").isZero();
        verify(ai, never()).generate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("엔진 목록: 데모 열람에게만 공용 한도의 오늘 사용량(quota)을 함께 준다")
    void enginesCarryDemoQuotaOnlyForDemo() {
        when(ai.engines()).thenAnswer(invocation -> new LinkedHashMap<>(Map.of("enabled", true)));
        AiController controller = controllerWithDemoLimit(mock(com.macrodash.store.UsageStore.class), 20);

        assertThat(controller.engines(demoRequest()))
                .containsEntry("quota", Map.of("used", 0, "limit", 20, "shared", true));
        assertThat(controller.engines(new org.springframework.mock.web.MockHttpServletRequest())).doesNotContainKey("quota");
    }

    /**
     * 실제 AiService — 허용 목록과 Claude 키만 정하고 나머지 키는 비웁니다(단위 테스트에는 @Value 주입이 없어 null).
     * 엔진을 못 쓰는 경로만 부르므로 네트워크에 나가지 않습니다.
     */
    private static AiService realAi(String allowList, String anthropicKey) {
        AiService service = new AiService(240, 60, 180, allowList);
        ReflectionTestUtils.setField(service, "anthropicKey", anthropicKey);
        for (String field : List.of("nvidiaKey", "cerebrasKey", "cloudflareAccountId", "cloudflareToken")) {
            ReflectionTestUtils.setField(service, field, "");
        }
        return service;
    }

    private static MockHttpServletRequest requestAs(CurrentUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        return request;
    }

    @Test
    @DisplayName("QA-133 엔진을 못 쓰면(AI 꺼짐·모르는 엔진·허용 목록 밖·못 쓰는 auto·키 없음) 한도를 쓰지 않고 원본 텍스트도 만들지 않는다")
    void unusableEngineConsumesNothing() {
        UsageStore store = mock(UsageStore.class);
        when(store.tryIncrement(anyLong(), any(), anyString(), anyInt())).thenReturn(true);
        UsageService usage = new UsageService(store, 20, 20);
        CurrentUser pro = new CurrentUser(7, "pro@test.local", "MEMBER", "PRO", null);

        record Case(String name, AiService ai, String engineId, String error) {
        }
        List<Case> cases = List.of(
                new Case("AI 꺼짐", realAi("", ""), "anthropic_haiku", "AI 키가 하나도 설정되지 않았습니다"),
                new Case("모르는 엔진", realAi("", "test-key"), "claude_opus", "지원하지 않는 AI 엔진입니다: claude_opus"),
                new Case("허용 목록 밖", realAi("anthropic_haiku", "test-key"), "cerebras_llama", "이 배포에서는 쓸 수 없는 엔진입니다"),
                new Case("못 쓰는 auto", realAi("anthropic_haiku", "test-key"), "auto", "엔진을 직접 고르세요"),
                new Case("허용됐지만 키 없음", realAi("", "test-key"), "cerebras_llama", "Llama 3.3 70B API Key 누락"));

        for (Case c : cases) {
            AiController controller = new AiController(c.ai(), new AiReportService(c.ai(), snapshotText, usage));
            for (CurrentUser user : List.of(DemoViewer.user(), pro)) {
                String what = c.name() + (user.demo() ? " · 데모" : " · 유료 회원");
                Map<String, Object> out = controller.report(
                        new AiController.ReportRequest(c.engineId(), "리스크 점검", null), requestAs(user));

                // 대시보드 전체 원본 텍스트(스냅샷 수십 개를 읽음)는 엔진을 부를 때만 만듭니다.
                verify(snapshotText, never()).fullText();
                verify(store, never()).tryIncrement(anyLong(), any(), anyString(), anyInt());
                assertThat(out).as(what).containsEntry("status", false).containsEntry("charged", false)
                        .containsEntry("reportType", "리스크 점검")
                        .containsEntry("quota", user.demo()
                                ? Map.of("used", 0, "limit", 20, "shared", true)
                                : Map.of("used", 0, "limit", 20))
                        .doesNotContainKey("promptChars");
                assertThat(String.valueOf(out.get("error"))).as(what).contains(c.error());
            }
        }
        verify(store, never()).decrement(anyLong(), any(), anyString());
        assertThat(usage.demoQuota().used()).isZero();
    }

    @Test
    @DisplayName("QA-133 오늘 한도를 다 쓴 회원이 못 쓰는 엔진을 고르면 한도 문구가 아니라 엔진 실패를 받고, 한도는 건드리지 않는다")
    void unusableEngineWinsOverExhaustedQuota() {
        // 엔진 판정이 한도 확인보다 먼저입니다 — 한도가 남아 있어도 이 엔진으로는 만들 수 없다는 쪽이 고칠 것을 알려 줍니다.
        UsageStore store = mock(UsageStore.class);
        when(store.count(anyLong(), any(), anyString())).thenReturn(20);
        UsageService usage = new UsageService(store, 20, 20);
        CurrentUser pro = new CurrentUser(7, "pro@test.local", "MEMBER", "PRO", null);
        AiService unusableAi = realAi("anthropic_haiku", "test-key");

        Map<String, Object> out = new AiController(unusableAi, new AiReportService(unusableAi, snapshotText, usage))
                .report(new AiController.ReportRequest("cerebras_llama", "리스크 점검", null), requestAs(pro));

        assertThat(out).containsEntry("status", false).containsEntry("quota", Map.of("used", 20, "limit", 20));
        assertThat(String.valueOf(out.get("error"))).contains("이 배포에서는 쓸 수 없는 엔진입니다")
                .doesNotContain("모두 썼습니다");
        verify(store, never()).tryIncrement(anyLong(), any(), anyString(), anyInt());
        verify(snapshotText, never()).fullText();
    }
}
