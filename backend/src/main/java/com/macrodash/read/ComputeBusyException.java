package com.macrodash.read;

import java.time.Duration;

/**
 * 비싼 계산 자리가 나지 않아 지금은 계산하지 못함 → HTTP 429 + {@code Retry-After}({@link ComputedCache}, QA-131).
 *
 * <p>read는 support를 쓰지 않으므로(ADR 0001) {@code support.TooManyRequestsException} 대신 이 예외를 던지고,
 * {@code web.ApiExceptionHandler}가 같은 429 모양으로 바꿉니다. 메시지는 그대로 사용자에게 보입니다.
 */
public class ComputeBusyException extends RuntimeException {

    private final Duration retryAfter;

    public ComputeBusyException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
