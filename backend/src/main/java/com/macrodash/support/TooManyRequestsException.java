package com.macrodash.support;

import java.time.Duration;

/**
 * 지금은 처리할 여유가 없으니 잠시 뒤 다시 시도하라 → HTTP 429 + {@code Retry-After}.
 *
 * <p>응답 변환은 {@code web.ApiExceptionHandler}가 합니다. 메시지는 그대로 사용자에게 보입니다.
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
