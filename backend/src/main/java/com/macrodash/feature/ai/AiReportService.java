package com.macrodash.feature.ai;

import com.macrodash.feature.snapshot.SnapshotTextService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 수집 데이터 기반 AI 리포트 — 리포트 종류 고르기, 원본 텍스트로 프롬프트 조립, 엔진 호출.
 *
 * <p>프롬프트에는 대시보드 원본 텍스트({@link SnapshotTextService})가 그대로 들어갑니다. AI가
 * 데이터에 없는 수치를 지어내지 않도록 "주어진 데이터만 근거로 삼으라"는 지시와 추정치 경고가
 * 리포트 종류별 시스템 프롬프트에 함께 들어 있습니다.
 */
@Service
public class AiReportService {

    private final AiService ai;
    private final SnapshotTextService snapshotText;

    public AiReportService(AiService ai, SnapshotTextService snapshotText) {
        this.ai = ai;
        this.snapshotText = snapshotText;
    }

    /** 고를 수 있는 리포트 종류(화면 표시 순서). */
    public Set<String> reportTypes() {
        return snapshotText.reportPrompts().keySet();
    }

    /** AI에 넣는 원본 텍스트. */
    public String inputText() {
        return snapshotText.fullText();
    }

    /**
     * 리포트를 만듭니다.
     *
     * @param engineId         엔진 ID ({@code auto}면 자동 선택)
     * @param requestedType    리포트 종류. 없거나 모르는 종류면 첫 번째 종류
     * @param extraInstruction 사용자가 덧붙인 지시(비어 있으면 넣지 않음). 길이 검사는 호출하는 쪽에서
     * @return 엔진 응답 + {@code reportType}(실제로 쓴 종류) · {@code promptChars}(프롬프트 길이)
     */
    public Map<String, Object> report(String engineId, String requestedType, String extraInstruction) {
        Map<String, String> prompts = snapshotText.reportPrompts();
        String reportType = (requestedType == null || !prompts.containsKey(requestedType))
                ? prompts.keySet().iterator().next()
                : requestedType;

        String systemPrompt = prompts.get(reportType);
        String data = snapshotText.fullText();

        StringBuilder prompt = new StringBuilder();
        prompt.append("아래는 대시보드가 수집한 최신 원본 데이터입니다.\n\n");
        prompt.append(data);
        prompt.append("\n\n요청: ").append(reportType).append("을(를) 작성하십시오.");
        if (extraInstruction != null && !extraInstruction.isBlank()) {
            prompt.append("\n추가 지시: ").append(extraInstruction);
        }

        Map<String, Object> result = ai.generate(engineId, prompt.toString(), systemPrompt);

        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("reportType", reportType);
        out.put("promptChars", prompt.length());
        return out;
    }
}
