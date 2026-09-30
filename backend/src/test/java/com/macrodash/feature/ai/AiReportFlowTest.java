package com.macrodash.feature.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        Map<String, Object> out = controller().report(
                new AiController.ReportRequest("cerebras_llama", "리스크 점검", "짧게"));

        String expectedPrompt = "아래는 대시보드가 수집한 최신 원본 데이터입니다.\n\nDATA"
                + "\n\n요청: 리스크 점검을(를) 작성하십시오.\n추가 지시: 짧게";
        verify(ai).generate("cerebras_llama", expectedPrompt, "SYSTEM-B");
        assertThat(out).containsEntry("text", "결과").containsEntry("engine", "cerebras_llama")
                .containsEntry("reportType", "리스크 점검")
                .containsEntry("promptChars", expectedPrompt.length());
    }

    @Test
    @DisplayName("모르는·빈 리포트 종류는 첫 번째 종류로, 빈 추가 지시는 붙이지 않는다")
    void unknownTypeFallsBackToFirst() {
        Map<String, Object> out = controller().report(new AiController.ReportRequest("auto", "없는 종류", " "));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).generate(eq("auto"), prompt.capture(), eq("SYSTEM-A"));
        assertThat(prompt.getValue()).endsWith("요청: 종합 매크로 브리핑을(를) 작성하십시오.");
        assertThat(out).containsEntry("reportType", "종합 매크로 브리핑");
    }

    @Test
    @DisplayName("추가 지시가 2,000자를 넘으면 엔진을 부르지 않고 400")
    void overlongInstructionIsRejected() {
        String longText = "가".repeat(2001);

        assertThatThrownBy(() -> controller().report(new AiController.ReportRequest("auto", null, longText)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("2000자 이하");
        verify(ai, never()).generate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("리포트 종류 목록과 원본 텍스트 응답 모양")
    void typesAndText() {
        assertThat(controller().reportTypes().get("types").toString()).isEqualTo("[종합 매크로 브리핑, 리스크 점검]");
        assertThat(controller().snapshotText()).isEqualTo(Map.of("text", "DATA"));
    }

    private AiController controller() {
        return new AiController(ai, snapshotText);
    }
}
