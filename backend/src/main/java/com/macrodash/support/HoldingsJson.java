package com.macrodash.support;

import com.macrodash.analytics.Holding;
import tools.jackson.databind.JsonNode;

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
