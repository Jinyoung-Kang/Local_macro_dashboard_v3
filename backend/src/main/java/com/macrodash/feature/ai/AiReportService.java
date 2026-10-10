package com.macrodash.feature.ai;

import com.macrodash.feature.auth.UsageService;
import com.macrodash.feature.snapshot.SnapshotTextService;
import com.macrodash.support.CurrentUser;
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
    private final UsageService usage;

    public AiReportService(AiService ai, SnapshotTextService snapshotText, UsageService usage) {
        this.ai = ai;
        this.snapshotText = snapshotText;
        this.usage = usage;
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
    public Map<String, Object> report(String engineId, String requestedType, String extraInstruction,
                                      CurrentUser user) {
        // 유료 API 비용 상한 — 사용자별 하루 한도(운영자는 한도 없음), 데모 열람은 방문자 전체의 공용 하루 한도(ADR 0014).
        // 엔진이 모두 실패하면 되돌립니다.
        UsageService.Quota quota = user == null ? null : usage.consume(user, UsageService.AI_REPORT);
        if (quota != null && !quota.allowed()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", false);
            out.put("error", quota.shared()
                    ? "데모의 오늘 AI 리포트 한도(%d건)를 모두 썼습니다. 내일(KST 0시) 다시 시도해 주세요."
                            .formatted(quota.limit())
                    : "오늘 쓸 수 있는 AI 리포트 %d건을 모두 썼습니다. 내일(KST 0시) 다시 쓸 수 있습니다."
                            .formatted(quota.limit()));
            out.put("quota", quotaMap(quota));
            return out;
        }
        Map<String, String> prompts = snapshotText.reportPrompts();
        String reportType = (requestedType == null || !prompts.containsKey(requestedType))
                ? prompts.keySet().iterator().next()
                : requestedType;

        String systemPrompt = prompts.get(reportType);
        String data = snapshotText.fullText();

        // 데이터를 태그로 감싸 지시와 섞이지 않게 하고, 요청은 긴 데이터 뒤(끝)에 둡니다.
        StringBuilder prompt = new StringBuilder();
        prompt.append("아래는 대시보드가 수집한 최신 원본 데이터입니다.\n\n<data>\n");
        prompt.append(data);
        prompt.append("\n</data>\n\n요청: ").append(reportType)
                .append("을(를) 작성하십시오. 시스템 지시의 [절 구성]과 [형식]을 따르십시오.");
        if (extraInstruction != null && !extraInstruction.isBlank()) {
            prompt.append("\n추가 지시: ").append(extraInstruction);
        }

        Map<String, Object> result = ai.generate(engineId, prompt.toString(), systemPrompt);

        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("reportType", reportType);
        out.put("promptChars", prompt.length());
        if (quota != null) {
            if (!Boolean.TRUE.equals(result.get("status"))) {
                usage.refund(user, UsageService.AI_REPORT);
                quota = quota.refunded();
            }
            out.put("quota", quotaMap(quota));
        }
        return out;
    }

    /**
     * 데모 열람이면 오늘의 공용 AI 리포트 사용량({@code used}·{@code limit}·{@code shared: true}), 아니면 null.
     * 화면이 생성 버튼 옆에 "데모 방문자 전체가 하루 N건을 함께 씁니다"를 적을 때 씁니다(엔진 목록 응답에 붙음).
     */
    public Map<String, Object> sharedQuota(CurrentUser user) {
        return user != null && user.demo() ? quotaMap(usage.demoQuota()) : null;
    }

    /** 응답의 {@code quota}. 데모 열람의 공용 한도에만 {@code shared: true}를 붙입니다(계정 사용자의 모양은 그대로). */
    private static Map<String, Object> quotaMap(UsageService.Quota quota) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used", quota.used());
        out.put("limit", quota.limit());
        if (quota.shared()) {
            out.put("shared", true);
        }
        return out;
    }
}
