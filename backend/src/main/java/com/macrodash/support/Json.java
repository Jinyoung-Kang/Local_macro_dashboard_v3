package com.macrodash.support;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.NavigableMap;

/**
 * 수집기가 적재한 JSON을 안전하게 읽는 헬퍼.
 *
 * <p><b>모든 접근자는 값이 없거나 형이 다르면 {@code null} 또는 빈 목록</b>을
 * 돌려줍니다. 예외를 던지지도, 0으로 대체하지도 않습니다 — 수집 실패가 화면에서
 * "보합"으로 읽히는 것을 막기 위해서입니다.
 *
 * <p>저장본은 외부 소스에서 온 것이라 필드가 없거나 타입이 바뀔 수 있습니다.
 * 저장본을 읽는 코드는 이 클래스만 쓰고 {@code JsonNode}를 직접 다루지 마세요.
 */
public final class Json {

    private Json() {
    }

    public static Double asDouble(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    public static String asText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        // asString(""): 객체·배열이면 "" (Jackson 2의 asText()와 같은 결과, Jackson 3의 asString()은 예외)
        return (value == null || value.isNull()) ? null : value.asString("");
    }

    public static boolean asBoolean(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }

    public static JsonNode child(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value;
    }

    public static List<JsonNode> array(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        value.forEach(out::add);
        return out;
    }

    /** {"points":[{"date","value"}]} 형태에서 값만 추립니다. */
    public static List<Double> pointValues(JsonNode payload) {
        List<Double> values = new ArrayList<>();
        for (JsonNode point : array(payload, "points")) {
            Double value = asDouble(point, "value");
            if (value == null) {
                value = asDouble(point, "close");
            }
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * {@code {"dates":[…], "<valuesField>":[…]}} 짝을 날짜 → 값 지도로. 같은 칸의 날짜·값이
     * 하나라도 비면 그 칸은 통째로 버립니다(배열이 한 칸씩 밀리지 않도록). cutoff 이전은 버립니다.
     *
     * <p>구루·스코어카드·환율·섹터·상관 화면이 저마다 같은 루프를 들고 있었습니다(4벌).
     */
    public static NavigableMap<LocalDate, Double> dateValueSeries(JsonNode entry, String datesField,
                                                                   String valuesField, LocalDate cutoff) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        JsonNode dates = child(entry, datesField);
        JsonNode values = child(entry, valuesField);
        if (dates == null || values == null || !dates.isArray() || !values.isArray()) {
            return out;
        }
        int size = Math.min(dates.size(), values.size());
        for (int i = 0; i < size; i++) {
            LocalDate date = parseDate(dates.get(i).asString(""));
            JsonNode value = values.get(i);
            if (date != null && value != null && value.isNumber()
                    && (cutoff == null || !date.isBefore(cutoff))) {
                out.put(date, value.asDouble());
            }
        }
        return out;
    }

    /**
     * {@code {"points":[{"date","value"|"close"}]}} 형태를 날짜 → 값 지도로. 날짜와 값이 둘 다 있는
     * 점만 넣습니다 — 점 단위로 읽어야 날짜가 깨진 점 하나가 뒤의 값을 한 칸씩 밀지 않습니다.
     */
    public static NavigableMap<LocalDate, Double> pointSeries(JsonNode payload) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        for (JsonNode point : array(payload, "points")) {
            Double value = asDouble(point, "value");
            if (value == null) {
                value = asDouble(point, "close");
            }
            LocalDate date = parseDate(asText(point, "date"));
            if (date != null && value != null) {
                out.put(date, value);
            }
        }
        return out;
    }

    public static LocalDate parseDate(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
