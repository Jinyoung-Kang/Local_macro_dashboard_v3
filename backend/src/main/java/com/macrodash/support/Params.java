package com.macrodash.support;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * URL 파라미터 해석·범위 정리.
 *
 * <p>원칙 — <b>형식이 틀린 값은 400</b>(무엇을 고쳐야 하는지 알려 줌), <b>범위만 벗어난
 * 숫자는 허용 범위로 접습니다</b>("최근 N년" 같은 값은 저장본보다 길면 결과가 같습니다).
 * 예전에는 둘 다 500이 되면서 파싱 예외 문구나 SQL 오류가 그대로 응답에 실렸습니다.
 */
public final class Params {

    /** 오류 메시지에 되돌려 보여 줄 입력값 최대 길이. */
    private static final int ECHO_LIMIT = 40;

    private Params() {
    }

    /**
     * {@code yyyy-MM-dd} 날짜 파라미터.
     *
     * @param name  파라미터 이름 (오류 메시지에 씁니다)
     * @param value 받은 값. 비어 있으면 "지정 안 함"
     * @return 날짜, 비어 있으면 null
     * @throws InvalidRequestException 형식이 틀리거나 없는 날짜(2026-13-45)
     */
    public static LocalDate optionalDate(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(
                    name + " 날짜 형식이 올바르지 않습니다: " + echo(value) + " (예: 2026-09-18)");
        }
    }

    /** {@code value}를 [min, max] 안으로 접습니다. */
    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /** 오류 메시지에 되돌려 줄 입력값 — 길면 자릅니다. */
    public static String echo(Object value) {
        String text = String.valueOf(value);
        return text.length() <= ECHO_LIMIT ? text : text.substring(0, ECHO_LIMIT) + "…";
    }
}
