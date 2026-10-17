package com.macrodash.support;

import com.macrodash.analytics.Holding;
import com.macrodash.analytics.HoldingsDiff;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

/**
 * 13F 저장본(JSON)의 보유 항목을 계산이 받는 {@link Holding}으로 바꿉니다.
 *
 * <p>읽는 규칙은 {@link Json}과 같습니다. 값이 없거나 형이 다르면 null이고, 0으로 채우지
 * 않습니다. CUSIP은 숫자로만 이루어질 수 있어 문자열로만 읽습니다.
 */
public final class HoldingsJson {

    private HoldingsJson() {
    }

    /** 분기 노드({@code {reportDate, holdings:[…]}})의 보유 항목 목록. 없으면 빈 목록. */
    public static List<Holding> holdings(JsonNode quarter) {
        return Json.array(quarter, "holdings").stream().map(HoldingsJson::holding).toList();
    }

    /**
     * {@code current}의 직전 분기 — 저장된 분기 가운데 보고일이 <b>바로 앞 분기 말일</b>인 것
     * ({@link HoldingsDiff#previousQuarterEnd}). 없으면 null(직전 분기 없음)입니다.
     *
     * <p>목록의 다음 항목을 그대로 쓰지 않습니다. 분기가 빠진 저장본에서는 그 항목이 6개월 이상 떨어진 공시라,
     * 반년치 변화가 '직전 분기 대비'로 나갔습니다(QA-085). 13F 화면·교집합·구루 회전율·신규 편입 알림이 모두 이 함수를 씁니다.
     *
     * @param quarters 한 기관의 분기 목록(같은 보고일은 하나만 남긴 것)
     */
    public static JsonNode previousQuarter(List<JsonNode> quarters, JsonNode current) {
        LocalDate target = HoldingsDiff.previousQuarterEnd(Json.parseDate(Json.asText(current, "reportDate")));
        if (target == null || quarters == null) {
            return null;
        }
        for (JsonNode quarter : quarters) {
            if (target.equals(Json.parseDate(Json.asText(quarter, "reportDate")))) {
                return quarter;
            }
        }
        return null;
    }

    public static Holding holding(JsonNode node) {
        return new Holding(
                Json.asText(node, "name"),
                Json.asText(node, "cusip"),
                Json.asText(node, "class"),
                Json.asDouble(node, "value"),
                Json.asDouble(node, "shares"),
                Json.asDouble(node, "weight"));
    }
}
