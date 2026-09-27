package com.macrodash.support;

/**
 * 요청을 처리하려면 수집기가 필요한데 닿지 못했습니다 → HTTP 502.
 *
 * <p>예전에는 이런 경우 200에 {@code ok:false}를 실어 보내, 상태 코드만으로는 실패를 알 수
 * 없었습니다. 응답 변환은 {@code web.ApiExceptionHandler}가 합니다.
 *
 * <p>주의사항 — 메시지는 그대로 사용자에게 보입니다. 무엇을 확인하면 되는지만 적으세요.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message) {
        super(message);
    }
}
