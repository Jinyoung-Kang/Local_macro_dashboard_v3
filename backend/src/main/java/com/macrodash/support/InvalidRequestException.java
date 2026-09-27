package com.macrodash.support;

/**
 * 요청 값이 잘못됐습니다 → HTTP 400.
 *
 * <p>서비스 계층이 웹 계층(ResponseStatusException 등)에 기대지 않고 "요청이 틀렸다"를
 * 알리려고 둡니다. 응답 변환은 {@code web.ApiExceptionHandler}가 합니다.
 *
 * <p>주의사항 — 메시지는 <b>그대로 사용자에게 보입니다</b>. 무엇을 고치면 되는지만 적고,
 * 예외 원문·SQL·내부 경로를 담지 마세요.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
