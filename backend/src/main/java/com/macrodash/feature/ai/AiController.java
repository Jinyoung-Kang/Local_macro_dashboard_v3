package com.macrodash.feature.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🤖 AI 종합 리포트 · 🤖 AI 연결 테스트.
 *
 * <p>같은 화면(🔌 연결 테스트)에 붙는 토스 조회는 {@link TossController}에 있습니다.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService ai;
    private final SnapshotTextService snapshotText;

    public AiController(AiService ai, SnapshotTextService snapshotText) {
        this.ai = ai;
        this.snapshotText = snapshotText;
    }

    @GetMapping("/engines")
    public Map<String, Object> engines() {
        return ai.engines();
    }

    @GetMapping("/report-types")
    public Map<String, Object> reportTypes() {
        return Map.of("types", snapshotText.reportPrompts().keySet());
    }

    /** 수집 데이터 원본 텍스트 (AI 입력 · 복사용). */
    @GetMapping("/snapshot-text")
    public Map<String, Object> snapshotText() {
        return Map.of("text", snapshotText.fullText());
    }

    public record ReportRequest(String engineId, String reportType, String extraInstruction) {
    }

    /**
     * 사용자가 덧붙이는 지시·테스트 프롬프트의 최대 길이.
     *
     * <p>이 글자는 그대로 유료 AI API로 전달됩니다. 상한이 없으면 요청 하나로
     * 토큰 한도·요금을 소진시키거나 응답을 수 분씩 붙잡아 둘 수 있습니다.
     */
    static final int MAX_USER_TEXT = 2000;

    /** 길이 초과면 400. 사용자가 무엇을 줄여야 하는지 알 수 있게 한도를 알려 줍니다. */
    static void requireShort(String text, String field) {
        if (text != null && text.length() > MAX_USER_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + "은(는) " + MAX_USER_TEXT + "자 이하로 입력하세요 (현재 " + text.length() + "자).");
        }
    }

    /**
     * 수집 데이터 기반 AI 리포트.
     *
     * <p>프롬프트에는 대시보드 원본 텍스트가 그대로 들어갑니다. AI가 데이터에
     * 없는 수치를 지어내지 않도록 "주어진 데이터만 근거로 삼으라"는 지시와
     * 추정치 경고가 함께 전달됩니다.
     */
    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody ReportRequest request) {
        requireShort(request.extraInstruction(), "추가 지시");
        Map<String, String> prompts = snapshotText.reportPrompts();
        String reportType = (request.reportType() == null || !prompts.containsKey(request.reportType()))
                ? prompts.keySet().iterator().next()
                : request.reportType();

        String systemPrompt = prompts.get(reportType);
        String data = snapshotText.fullText();

        StringBuilder prompt = new StringBuilder();
        prompt.append("아래는 대시보드가 수집한 최신 원본 데이터입니다.\n\n");
        prompt.append(data);
        prompt.append("\n\n요청: ").append(reportType).append("을(를) 작성하십시오.");
        if (request.extraInstruction() != null && !request.extraInstruction().isBlank()) {
            prompt.append("\n추가 지시: ").append(request.extraInstruction());
        }

        Map<String, Object> result = ai.generate(request.engineId(), prompt.toString(), systemPrompt);

        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("reportType", reportType);
        out.put("promptChars", prompt.length());
        return out;
    }

    /** AI 연결 테스트 (짧은 프롬프트로 엔진 응답만 확인). */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam(defaultValue = "auto") String engineId,
                                    @RequestParam(required = false) String prompt) {
        requireShort(prompt, "테스트 프롬프트");
        String text = (prompt == null || prompt.isBlank())
                ? "한국어로 한 문장만 답하십시오: 지금 연결이 정상인지 알려 주세요."
                : prompt;
        return ai.generate(engineId, text, "간결하게 한국어로 답하십시오.");
    }
}
