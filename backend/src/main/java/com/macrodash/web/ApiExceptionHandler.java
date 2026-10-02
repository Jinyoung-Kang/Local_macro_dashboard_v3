package com.macrodash.web;

import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.Params;
import com.macrodash.support.UpstreamUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Locale;

/**
 * API 오류 응답을 한 모양으로 맞춥니다: {@code {"error": "<코드>", "message": "<설명>"}}.
 *
 * <p>왜 필요한가 — 예전에는 세 가지가 섞여 있었습니다. 세션 필터는 {@code {error, message}},
 * Spring 기본 오류는 {@code {timestamp, status, error, message, path}}, 일부 경로는 200에
 * {@code ok:false}. 게다가 {@code server.error.include-message: always}라 500 응답에
 * 파싱 예외 문구와 SQL 문장이 그대로 실렸습니다.
 *
 * <p>규칙
 * <ul>
 *   <li>{@code error}는 HTTP 상태 이름의 소문자(bad_request, not_found …). 세션 필터의
 *       {@code unauthorized}와 같은 규칙입니다.</li>
 *   <li>{@code message}는 사용자가 읽는 한국어 설명. 예외 원문·자바 타입·SQL을 담지 않습니다.</li>
 *   <li>예상하지 못한 예외는 500과 일반 문구만 내보내고, 원인은 서버 로그에만 남깁니다.</li>
 * </ul>
 * 화면({@code lib/api.ts})은 {@code message}만 읽으므로 이 형식과 호환됩니다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 오류 응답 본문. */
    public record ApiError(String error, String message) {
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(InvalidRequestException e) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 수집기가 필요한 작업인데 닿지 못함. 예전에는 200에 ok:false였습니다. */
    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ApiError> upstreamUnavailable(UpstreamUnavailableException e) {
        return respond(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * DB에 연결하지 못함(QA-009). 코드 결함(500)이 아니라 의존 서비스 장애라 503이고, 사용자가 볼 곳을
     * 알려 줍니다. {@code CannotGetJdbcConnectionException}이 이 예외의 하위 타입입니다. {@code /api/health}가
     * 같은 상황에서 503 + database:unreachable을 돌려주는 것과 맞춥니다. 원인 문구(JDBC·SQLState)는 로그에만.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ApiError> databaseUnavailable(DataAccessResourceFailureException e, HttpServletRequest request) {
        log.warn("DB에 연결하지 못했습니다 ({} {}): {}", request.getMethod(), request.getRequestURI(),
                e.getMostSpecificCause().getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, messageFor(503));
    }

    /** {@code topN=abc}처럼 숫자·불리언 자리에 다른 값이 온 경우. 자바 타입 이름은 내보내지 않습니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST,
                "'" + e.getName() + "' 값이 올바르지 않습니다: " + Params.echo(e.getValue()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return respond(HttpStatus.BAD_REQUEST, "필수 파라미터 '" + e.getParameterName() + "'가 없습니다.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽지 못했습니다.");
    }

    /**
     * 그 밖의 모든 예외.
     *
     * <p>Spring MVC 표준 예외(없는 경로 404, 메서드 405, 형식 415 …)와
     * ResponseStatusException은 {@link ErrorResponse}로 상태 코드를 알고 있어 그대로 씁니다.
     * 그 외는 예상하지 못한 오류라 500 + 일반 문구로 답하고 원인은 로그에만 남깁니다.
     *
     * <p>주의사항 — 브라우저가 화면을 옮기며 요청을 끊은 경우(클라이언트 연결 끊김)는
     * 쓸 수 있는 응답이 없으므로 아무것도 쓰지 않고, 오류 로그도 남기지 않습니다.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
            log.debug("클라이언트가 연결을 끊었습니다 ({} {})", request.getMethod(), request.getRequestURI());
            return null;
        }
        if (e instanceof ErrorResponse known) {
            HttpStatusCode status = known.getStatusCode();
            String reason = e instanceof ResponseStatusException rse ? rse.getReason() : null;
            return respond(status, reason != null ? reason : messageFor(status.value()));
        }
        log.error("처리하지 못한 예외 ({} {})", request.getMethod(), request.getRequestURI(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, messageFor(500));
    }

    private static ResponseEntity<ApiError> respond(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ApiError(errorCode(status.value()), message));
    }

    /** HTTP 상태 → 오류 코드 (예: 404 → {@code not_found}). */
    public static String errorCode(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known == null ? "error" : known.name().toLowerCase(Locale.ROOT);
    }

    /** 상태별 기본 설명. 원인 문구를 대신합니다. */
    public static String messageFor(int status) {
        return switch (status) {
            case 400 -> "요청 형식이 올바르지 않습니다.";
            case 401 -> "로그인이 필요합니다.";
            case 403 -> "접근할 수 없습니다.";
            case 404 -> "요청한 주소를 찾을 수 없습니다.";
            case 405 -> "허용되지 않는 HTTP 메서드입니다.";
            case 415 -> "지원하지 않는 요청 형식입니다.";
            case 503 -> "데이터베이스에 연결할 수 없습니다. PostgreSQL이 켜져 있는지 확인하세요 (make logs S=postgres).";
            default -> status >= 500
                    ? "서버 내부 오류가 발생했습니다. 백엔드 로그를 확인하세요."
                    : "요청을 처리할 수 없습니다.";
        };
    }
}
