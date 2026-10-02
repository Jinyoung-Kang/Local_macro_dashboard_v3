package com.macrodash.analytics;

/**
 * 13F 보유 항목 하나 (수집기가 정규화한 모양).
 *
 * <p>값을 모르면 {@code null}입니다. 0으로 채우지 않습니다 — 13F 공시에는 주식 수가 빠진
 * 항목이 실제로 있고, 그 "모름"이 분류까지 그대로 전달되어야 합니다.
 *
 * <p>저장본(JSON)에서 이 모양으로 바꾸는 일은 {@code support.HoldingsJson}이 합니다.
 *
 * @param name   발행사 이름(대문자). 같은 회사가 클래스별로 여러 항목일 수 있습니다
 * @param cusip  CUSIP — 숫자로만 이루어질 수 있어 반드시 문자열. 모르면 null 또는 빈 문자열
 * @param cls    주식 종류(COM, CL A, CL C …)
 * @param value  평가액(달러). 모르면 null
 * @param shares 주식 수. 모르면 null
 * @param weight 포트폴리오 비중(%). 모르면 null
 */
public record Holding(String name, String cusip, String cls, Double value, Double shares, Double weight) {
}
